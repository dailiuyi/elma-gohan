from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import secrets
import subprocess
import threading
from typing import Callable, Sequence
from urllib.parse import urlsplit
import webbrowser


GUIDE_DIR = Path(__file__).resolve().parent
REPO_ROOT = GUIDE_DIR.parents[1]
DEFAULT_TEMPLATE = GUIDE_DIR / "index.html"
DEFAULT_DASHBOARD = GUIDE_DIR / "dashboard.local.html"
REFRESH_SCRIPT = GUIDE_DIR / "generate_from_production.ps1"
REFRESH_CONTROLS = 'id="dashboard-refresh-controls"'
BRIDGE_MARKER = "<!-- ELMA_DASHBOARD_REFRESH_BRIDGE -->"
OFFLINE_CONNECT_POLICY = "connect-src 'none'"
LOCAL_CONNECT_POLICY = "connect-src 'self'"


class DashboardServerError(RuntimeError):
    pass


@dataclass(frozen=True)
class RefreshResult:
    output: Path
    size: int
    updated_at: str


def _bridge_script(token: str, days: int) -> str:
    safe_token = json.dumps(token)
    return f"""{BRIDGE_MARKER}
<script>
(() => {{
  const controls = document.getElementById('dashboard-refresh-controls');
  const button = document.getElementById('dashboard-refresh-button');
  const status = document.getElementById('dashboard-refresh-status');
  if (!controls || !button || !status) return;

  const refreshToken = {safe_token};
  const defaultLabel = '刷新生产数据';
  controls.hidden = false;
  status.textContent = '最近 {days} 天 · 仅本机只读刷新';

  button.addEventListener('click', async () => {{
    button.disabled = true;
    button.setAttribute('aria-busy', 'true');
    button.textContent = '正在刷新…';
    status.textContent = '正在通过 SSH 拉取只读聚合数据，请稍候';
    status.className = 'refresh-status';
    try {{
      const response = await fetch('/api/refresh', {{
        method: 'POST',
        headers: {{
          'Content-Type': 'application/json',
          'X-ELMA-Refresh-Token': refreshToken,
        }},
        body: '{{}}',
      }});
      const payload = await response.json();
      if (!response.ok) throw new Error(payload.message || '刷新失败');
      button.textContent = '刷新成功';
      status.textContent = '最新快照已生成，正在重新载入';
      status.className = 'refresh-status is-success';
      window.setTimeout(() => window.location.reload(), 350);
    }} catch (error) {{
      button.disabled = false;
      button.removeAttribute('aria-busy');
      button.textContent = defaultLabel;
      status.textContent = error instanceof Error ? error.message : '刷新失败，旧快照仍然保留';
      status.className = 'refresh-status is-error';
    }}
  }});
}})();
</script>"""


def inject_refresh_bridge(html: str, token: str, days: int) -> str:
    if REFRESH_CONTROLS not in html:
        raise DashboardServerError("看板底稿缺少刷新控件，请先更新 index.html 并重新生成看板。")
    if BRIDGE_MARKER in html:
        raise DashboardServerError("看板响应中已经存在刷新桥接脚本。")
    if OFFLINE_CONNECT_POLICY not in html:
        raise DashboardServerError("看板缺少预期的离线 CSP，拒绝放宽未知页面的连接策略。")
    if "</body>" not in html:
        raise DashboardServerError("看板 HTML 缺少 </body>。")
    served = html.replace(OFFLINE_CONNECT_POLICY, LOCAL_CONNECT_POLICY, 1)
    return served.replace("</body>", f"  {_bridge_script(token, days)}\n</body>", 1)


class DashboardRefresher:
    def __init__(
        self,
        output: Path,
        days: int,
        runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
    ) -> None:
        self.output = output.resolve()
        self.days = days
        self.runner = runner
        self._lock = threading.Lock()

    def refresh(self) -> RefreshResult:
        if not self._lock.acquire(blocking=False):
            raise DashboardServerError("已有刷新任务正在运行，请等待当前任务完成。")
        try:
            creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
            command: Sequence[str] = (
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(REFRESH_SCRIPT),
                "-Days",
                str(self.days),
                "-Output",
                str(self.output),
            )
            try:
                completed = self.runner(
                    command,
                    cwd=REPO_ROOT,
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    timeout=180,
                    check=False,
                    creationflags=creation_flags,
                )
            except subprocess.TimeoutExpired as exc:
                raise DashboardServerError("刷新超过 180 秒，已终止；旧快照仍然保留。") from exc
            except OSError as exc:
                raise DashboardServerError("无法启动生产刷新脚本；请检查 PowerShell 和 Python 配置。") from exc

            if completed.returncode != 0:
                raise DashboardServerError("生产刷新失败；请查看启动终端中的错误，旧快照仍然保留。")
            if "DASHBOARD_OK" not in completed.stdout or not self.output.is_file():
                raise DashboardServerError("刷新脚本未生成有效看板，旧快照仍然保留。")

            stat = self.output.stat()
            return RefreshResult(
                output=self.output,
                size=stat.st_size,
                updated_at=datetime.fromtimestamp(stat.st_mtime, tz=timezone.utc).isoformat(),
            )
        finally:
            self._lock.release()


class DashboardApplication:
    def __init__(self, output: Path, days: int) -> None:
        self.output = output.resolve()
        self.days = days
        self.token = secrets.token_urlsafe(32)
        self.refresher = DashboardRefresher(self.output, days)

    def read_dashboard(self) -> str:
        source = self.output if self.output.is_file() else DEFAULT_TEMPLATE
        try:
            html = source.read_text(encoding="utf-8")
        except OSError as exc:
            raise DashboardServerError(f"无法读取看板文件：{source.name}") from exc
        return inject_refresh_bridge(html, self.token, self.days)


class DashboardHttpServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, server_address, application: DashboardApplication):
        self.application = application
        super().__init__(server_address, DashboardRequestHandler)


class DashboardRequestHandler(BaseHTTPRequestHandler):
    server: DashboardHttpServer

    def log_message(self, format_string: str, *args) -> None:
        print(f"[{self.log_date_time_string()}] {format_string % args}")

    def _send_bytes(self, status: HTTPStatus, content_type: str, body: bytes) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
        self.end_headers()
        self.wfile.write(body)

    def _send_json(self, status: HTTPStatus, payload: dict[str, object]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self._send_bytes(status, "application/json; charset=utf-8", body)

    def do_GET(self) -> None:
        path = urlsplit(self.path).path
        if path in ("/", "/index.html"):
            try:
                body = self.server.application.read_dashboard().encode("utf-8")
            except DashboardServerError as exc:
                self._send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"message": str(exc)})
                return
            self._send_bytes(HTTPStatus.OK, "text/html; charset=utf-8", body)
            return
        if path == "/THIRD_PARTY_NOTICES.md":
            body = (GUIDE_DIR / "THIRD_PARTY_NOTICES.md").read_bytes()
            self._send_bytes(HTTPStatus.OK, "text/markdown; charset=utf-8", body)
            return
        if path == "/favicon.ico":
            self._send_bytes(HTTPStatus.NO_CONTENT, "image/x-icon", b"")
            return
        self._send_json(HTTPStatus.NOT_FOUND, {"message": "未找到该资源。"})

    def do_POST(self) -> None:
        path = urlsplit(self.path).path
        if path != "/api/refresh":
            self._send_json(HTTPStatus.NOT_FOUND, {"message": "未找到该接口。"})
            return
        try:
            content_length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            content_length = -1
        if content_length < 0 or content_length > 1024:
            self._send_json(HTTPStatus.BAD_REQUEST, {"message": "请求内容无效。"})
            return
        if content_length:
            self.rfile.read(content_length)
        port = self.server.server_address[1]
        allowed_origins = {f"http://127.0.0.1:{port}", f"http://localhost:{port}"}
        if self.headers.get("Origin") not in allowed_origins:
            self._send_json(HTTPStatus.FORBIDDEN, {"message": "只允许当前本机看板页面发起刷新。"})
            return
        if self.headers.get("X-ELMA-Refresh-Token") != self.server.application.token:
            self._send_json(HTTPStatus.FORBIDDEN, {"message": "刷新授权已失效，请重新打开页面。"})
            return
        if self.headers.get_content_type() != "application/json":
            self._send_json(HTTPStatus.BAD_REQUEST, {"message": "刷新请求格式无效。"})
            return
        try:
            result = self.server.application.refresher.refresh()
        except DashboardServerError as exc:
            status = HTTPStatus.CONFLICT if "正在运行" in str(exc) else HTTPStatus.BAD_GATEWAY
            self._send_json(status, {"message": str(exc)})
            return
        self._send_json(
            HTTPStatus.OK,
            {
                "message": "生产数据刷新成功。",
                "bytes": result.size,
                "updatedAt": result.updated_at,
            },
        )


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="在本机提供可一键刷新生产快照的 ELMA 运营看板。")
    parser.add_argument("--port", type=int, default=0, help="本机监听端口；默认由 Windows 自动分配可用端口")
    parser.add_argument("--days", type=int, default=30, help="统计窗口天数，默认 30")
    parser.add_argument("--output", type=Path, default=DEFAULT_DASHBOARD, help="生产看板输出路径")
    parser.add_argument("--open", action="store_true", help="启动后打开默认浏览器")
    args = parser.parse_args(argv)
    if not 0 <= args.port <= 65535:
        parser.error("--port 必须在 0 到 65535 之间")
    if not 1 <= args.days <= 366:
        parser.error("--days 必须在 1 到 366 之间")
    return args


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    application = DashboardApplication(args.output, args.days)
    server = DashboardHttpServer(("127.0.0.1", args.port), application)
    host, port = server.server_address
    url = f"http://{host}:{port}/"
    print(f"DASHBOARD_SERVER_OK url={url} days={args.days} output={application.output}")
    print("仅本机可访问；按 Ctrl+C 停止。")
    if args.open:
        threading.Timer(0.25, webbrowser.open, args=(url,)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n看板服务已停止。")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
