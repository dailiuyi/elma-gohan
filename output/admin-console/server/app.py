#!/usr/bin/env python3
"""Loopback-only admin data API; Nginx supplies TLS and existing authentication."""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
import multiprocessing
import os
from pathlib import Path
import re
import secrets
import tempfile
import threading
import time
from typing import Any
from urllib.parse import parse_qs, urlsplit

from collector import (CollectionError, SHANGHAI, collect_worker, now_iso,
    validate_range, validate_snapshot)

PREFIX = "/console/data/v1/"
ID_PATTERN = re.compile(r"^[0-9]{8}T[0-9]{12}-[a-f0-9]{12}$")
MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024


def atomic_json(path: Path, value: Any) -> None:
    data = json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    if len(data.encode("utf-8")) > MAX_SNAPSHOT_BYTES:
        raise CollectionError("快照超过大小上限，原快照已保留。")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n",
                prefix=".snapshot-", suffix=".tmp", dir=path.parent, delete=False) as output:
            temporary = Path(output.name)
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if temporary and temporary.exists():
            temporary.unlink()


class SnapshotStore:
    """Atomic immutable snapshots, newest valid 12 retained, no client paths."""
    def __init__(self, directory: Path, limit: int = 12):
        self.directory = directory.resolve()
        self.directory.mkdir(parents=True, exist_ok=True)
        self.limit = limit
        self.lock = threading.RLock()
        self.snapshots: dict[str, dict[str, Any]] = {}
        for path in sorted(self.directory.glob("*.json"), reverse=True):
            if not ID_PATTERN.fullmatch(path.stem) or path.is_symlink():
                continue
            try:
                if path.stat().st_size > MAX_SNAPSHOT_BYTES:
                    continue
                payload = json.loads(path.read_text(encoding="utf-8"))
                validate_snapshot(payload)
                if payload.get("id") != path.stem:
                    continue
                self.snapshots[path.stem] = payload
            except (OSError, ValueError, RuntimeError):
                continue
        self._prune()

    def _prune(self) -> None:
        for identifier in sorted(self.snapshots, reverse=True)[self.limit:]:
            try:
                (self.directory / f"{identifier}.json").unlink(missing_ok=True)
                self.snapshots.pop(identifier, None)
            except OSError:
                # Keep in-memory history bounded even if an old disk file cannot be removed.
                self.snapshots.pop(identifier, None)

    def save(self, payload: dict[str, Any]) -> str:
        with self.lock:
            validate_snapshot(payload)
            identifier = datetime.now(SHANGHAI).strftime("%Y%m%dT%H%M%S%f") + "-" + secrets.token_hex(6)
            payload = {**payload, "id": identifier}
            atomic_json(self.directory / f"{identifier}.json", payload)
            self.snapshots[identifier] = payload
            self._prune()
            return identifier

    def current_id(self) -> str | None:
        with self.lock:
            return max(self.snapshots, default=None)

    def history(self) -> list[dict[str, Any]]:
        with self.lock:
            return [{"id": identifier, "snapshotAt": payload["meta"].get("snapshotAt"),
                "from": payload["meta"].get("periodStart"), "to": payload["meta"].get("periodEnd")}
                for identifier, payload in sorted(self.snapshots.items(), reverse=True)]

    def get(self, identifier: str | None = None) -> dict[str, Any] | None:
        with self.lock:
            return self.snapshots.get(identifier or self.current_id())


class RefreshManager:
    def __init__(self, store: SnapshotStore, fixture: str | None = None,
                 budget: float = 180, worker: Any = collect_worker):
        self.store, self.fixture, self.budget, self.worker = store, fixture, budget, worker
        self.token = secrets.token_urlsafe(32)
        self.lock = threading.RLock()
        self.refresh: dict[str, Any] = {"running": False, "startedAt": None, "finishedAt": None, "error": None}
        self.thread: threading.Thread | None = None

    def state(self) -> dict[str, Any]:
        with self.lock:
            return {"csrfToken": self.token, "refresh": dict(self.refresh),
                "history": self.store.history(), "currentId": self.store.current_id()}

    def start(self, start: Any, end: Any) -> bool:
        validate_range(start, end)
        with self.lock:
            if self.refresh["running"]:
                return False
            self.refresh = {"running": True, "startedAt": now_iso(), "finishedAt": None,
                "error": None, "from": start, "to": end}
            self.thread = threading.Thread(target=self._run, args=(start, end), daemon=True,
                name="console-refresh-supervisor")
            self.thread.start()
            return True

    def _run(self, start: str, end: str) -> None:
        context = multiprocessing.get_context("spawn")
        receive, send = context.Pipe(duplex=False)
        process = context.Process(target=self.worker,
            args=(send, start, end, self.fixture, self.budget), daemon=True)
        error = None
        try:
            process.start()
            send.close()
            # The process is killable, including a stuck connection, query, or fixture read.
            if not receive.poll(self.budget):
                raise CollectionError("刷新超过 180 秒预算，原快照已保留。请缩短区间重试。")
            result = receive.recv()
            if not isinstance(result, dict) or not result.get("ok"):
                raise CollectionError(result.get("error", "刷新失败，原快照已保留。") if isinstance(result, dict)
                    else "数据服务返回无效结果，原快照已保留。")
            self.store.save(result["snapshot"])
        except CollectionError as exc:
            error = str(exc)
        except Exception:
            error = "刷新失败，原快照已保留。请检查服务配置或稍后重试。"
        finally:
            if process.pid:
                process.join(timeout=0.5)
                if process.is_alive():
                    process.terminate()
                    process.join(timeout=2)
                if process.is_alive():
                    process.kill()
                    process.join(timeout=2)
            receive.close()
            send.close()
            with self.lock:
                self.refresh.update(running=False, finishedAt=now_iso(), error=error)


def normalized_origin(value: str) -> tuple[str, str]:
    parsed = urlsplit(value)
    if (parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or
            parsed.password or parsed.path not in {"", "/"} or parsed.query or parsed.fragment):
        raise ValueError("origin 必须为 http(s)://host[:port]")
    port = parsed.port
    authority = parsed.hostname.lower()
    if ":" in authority:
        authority = f"[{authority}]"
    if port and port != (443 if parsed.scheme == "https" else 80):
        authority += f":{port}"
    return f"{parsed.scheme}://{authority}", authority


def handler_for(manager: RefreshManager, origin: str) -> type[BaseHTTPRequestHandler]:
    expected_origin, expected_host = normalized_origin(origin)

    class Handler(BaseHTTPRequestHandler):
        server_version = "ElmaConsole"
        sys_version = ""

        def setup(self) -> None:
            super().setup()
            self.connection.settimeout(10)

        def log_message(self, format: str, *args: Any) -> None:
            # No URLs, request bodies, Authorization values or DB error details in logs.
            pass

        def reply(self, status: int, payload: Any) -> None:
            body = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.end_headers()
            self.wfile.write(body)

        def check_headers(self, post: bool = False) -> bool:
            hosts = self.headers.get_all("Host", [])
            if len(hosts) != 1 or hosts[0].lower() != expected_host:
                self.reply(403, {"error": "请求 Host 不被允许。"})
                return False
            origins = self.headers.get_all("Origin", [])
            if post and len(origins) != 1:
                self.reply(403, {"error": "需要同源请求。"})
                return False
            if origins and (len(origins) != 1 or origins[0] != expected_origin):
                self.reply(403, {"error": "请求来源不被允许。"})
                return False
            if post:
                tokens = self.headers.get_all("X-ELMA-CSRF", [])
                if len(tokens) != 1 or not secrets.compare_digest(tokens[0].encode("utf-8"), manager.token.encode("ascii")):
                    self.reply(403, {"error": "刷新令牌已失效，请重新加载页面。"})
                    return False
            return True

        def do_GET(self) -> None:
            if not self.check_headers():
                return
            parsed = urlsplit(self.path)
            if parsed.path == PREFIX + "state":
                self.reply(200, manager.state())
            elif parsed.path == PREFIX + "snapshot":
                params = parse_qs(parsed.query, keep_blank_values=True)
                ids = params.get("id", [])
                if set(params) - {"id"} or len(ids) > 1 or (ids and not ID_PATTERN.fullmatch(ids[0])):
                    self.reply(400, {"error": "快照 ID 无效。"})
                    return
                snapshot = manager.store.get(ids[0] if ids else None)
                self.reply(200 if snapshot else 404, snapshot or {"error": "尚无快照，请手动拉取数据。"})
            else:
                self.reply(404, {"error": "接口不存在。"})

        def do_POST(self) -> None:
            if not self.check_headers(post=True):
                return
            if self.path != PREFIX + "refresh":
                self.reply(404, {"error": "接口不存在。"})
                return
            if self.headers.get("Content-Type", "").split(";")[0].strip().lower() != "application/json":
                self.reply(415, {"error": "请求须使用 application/json。"})
                return
            lengths = self.headers.get_all("Content-Length", [])
            if self.headers.get("Transfer-Encoding") or len(lengths) != 1 or not lengths[0].isdigit():
                self.reply(400, {"error": "请求长度无效。"})
                return
            length = int(lengths[0])
            if not 2 <= length <= 1024:
                self.reply(413, {"error": "请求体大小无效。"})
                return
            try:
                body = json.loads(self.rfile.read(length))
                if not isinstance(body, dict) or set(body) != {"from", "to"}:
                    raise CollectionError("请求只接受 from 和 to 日期。")
                started = manager.start(body["from"], body["to"])
            except (CollectionError, ValueError, UnicodeError) as exc:
                self.reply(400, {"error": str(exc) if isinstance(exc, CollectionError) else "请求 JSON 无效。"})
                return
            self.reply(202 if started else 409, manager.state() if started else {"error": "数据正在刷新，请等待当前任务完成。"})

        def do_OPTIONS(self) -> None:
            self.reply(405, {"error": "仅支持同源 GET/POST。"})

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(description="ELMA read-only admin aggregate service")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8092)
    parser.add_argument("--snapshot-dir", type=Path, required=True)
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--origin", default="https://elma-gohan.xyz")
    args = parser.parse_args()
    if not ipaddress.ip_address(args.host).is_loopback or ":" in args.host:
        parser.error("服务必须绑定 IPv4 loopback 地址。")
    try:
        normalized_origin(args.origin)
    except ValueError as exc:
        parser.error(str(exc))
    manager = RefreshManager(SnapshotStore(args.snapshot_dir), str(args.fixture.resolve()) if args.fixture else None)
    server = ThreadingHTTPServer((args.host, args.port), handler_for(manager, args.origin))
    server.daemon_threads = True
    print(f"CONSOLE_READY http://{args.host}:{args.port}{PREFIX} read_only=true", flush=True)
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    multiprocessing.freeze_support()
    main()
