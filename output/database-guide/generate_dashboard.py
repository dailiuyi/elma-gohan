#!/usr/bin/env python3
"""Generate a self-contained, read-only ELMA Gohan operations dashboard."""

from __future__ import annotations

import argparse
from contextlib import AbstractContextManager
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
import json
import getpass
import os
from pathlib import Path
import re
import sys
import tempfile
import time
from typing import Any, Mapping, Sequence

from dashboard_queries import (
    ALGORITHM_DISTRIBUTION_V1,
    ALGORITHM_DISTRIBUTION_V6,
    BEHAVIOR_DISTRIBUTION,
    CAPABILITIES,
    CATEGORY_DISTRIBUTION,
    CONNECTION_META,
    DAILY_V1,
    DAILY_V6,
    FEEDBACK_DISTRIBUTION,
    FLYWAY_VERSION,
    FUNNEL_V1,
    FUNNEL_V6,
    LOCATION_GRIDS,
    OVERVIEW,
    RISK_CALIBRATION,
    RISK_DISTRIBUTION,
    SHADOW_SELECTION_REASONS,
    SHADOW_SUMMARY,
    SHADOW_VARIANTS,
    TABLE_COUNTS_V1,
    TABLE_COUNTS_V3,
    TABLE_COUNTS_V4,
    TABLE_COUNTS_V6,
    TABLE_COUNTS_V9,
    QuerySpec,
    validate_read_only_query,
    validate_registry,
)
from prefecture_lookup import PrefectureGridIndex


SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_HTML = SCRIPT_DIR / "index.html"
DATA_START = "<!-- ELMA_DASHBOARD_DATA_START -->"
DATA_END = "<!-- ELMA_DASHBOARD_DATA_END -->"
UUID_PATTERN = re.compile(
    r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\b"
)
_PREFECTURE_INDEX: PrefectureGridIndex | None = None


class DashboardError(RuntimeError):
    """A safe, user-facing dashboard generation failure."""


@dataclass(frozen=True)
class DashboardConfig:
    days: int = 30
    max_categories: int = 8
    max_map_points: int = 120
    min_map_users: int = 1
    low_sample_threshold: int = 3


def _camel_key(key: str) -> str:
    head, *tail = key.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in tail)


def _json_value(value: Any) -> Any:
    if isinstance(value, Decimal):
        return int(value) if value == value.to_integral_value() else float(value)
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, Mapping):
        return {_camel_key(str(key)): _json_value(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_value(item) for item in value]
    return value


def _camel_row(row: Mapping[str, Any] | None) -> dict[str, Any]:
    return _json_value(dict(row or {}))


def _safe_json_dumps(payload: Mapping[str, Any]) -> str:
    raw = json.dumps(_json_value(payload), ensure_ascii=False, separators=(",", ":"))
    return (
        raw.replace("&", "\\u0026")
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
    )


class PostgresQueryRunner(AbstractContextManager["PostgresQueryRunner"]):
    """Execute only registered bounded queries in one read-only transaction."""

    def __init__(self, connection: Any):
        self.connection = connection
        self.query_count = 0

    @classmethod
    def connect_from_environment(cls, password: str | None = None) -> "PostgresQueryRunner":
        try:
            import psycopg
            from psycopg.rows import dict_row
        except ImportError as exc:
            raise DashboardError(
                "缺少 psycopg。请先执行：py -m pip install -r "
                "output/database-guide/requirements.txt"
            ) from exc

        conninfo = os.environ.get("DATABASE_URL", "")
        try:
            connection_args = {
                "row_factory": dict_row,
                "connect_timeout": 10,
                "application_name": "elma-offline-dashboard",
                "options": (
                    "-c default_transaction_read_only=on "
                    "-c statement_timeout=15000 "
                    "-c lock_timeout=3000"
                ),
            }
            if password is not None:
                connection_args["password"] = password
            connection = psycopg.connect(conninfo, **connection_args)
            connection.read_only = True
        except Exception as exc:
            raise DashboardError(f"数据库连接失败：{exc}") from exc

        runner = cls(connection)
        try:
            with connection.cursor() as cursor:
                cursor.execute("SHOW transaction_read_only")
                status = cursor.fetchone()
            value = next(iter(status.values())) if isinstance(status, Mapping) else status[0]
            if str(value).lower() not in {"on", "true", "1"}:
                raise DashboardError("数据库会话没有进入只读模式，已中止生成")
        except Exception:
            connection.close()
            raise
        return runner

    def run(self, spec: QuerySpec, params: Sequence[Any] = ()) -> list[dict[str, Any]]:
        validate_read_only_query(spec)
        try:
            with self.connection.cursor() as cursor:
                cursor.execute(spec.sql, tuple(params))
                rows = cursor.fetchmany(spec.max_rows + 1)
        except Exception as exc:
            raise DashboardError(f"聚合查询 {spec.name} 执行失败：{exc}") from exc
        if len(rows) > spec.max_rows:
            raise DashboardError(
                f"聚合查询 {spec.name} 返回超过 {spec.max_rows} 行，已拒绝载入"
            )
        self.query_count += 1
        return [dict(row) if isinstance(row, Mapping) else dict(row) for row in rows]

    def __exit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
        try:
            self.connection.rollback()
        finally:
            self.connection.close()


def _one(runner: Any, spec: QuerySpec, params: Sequence[Any] = ()) -> dict[str, Any]:
    rows = runner.run(spec, params)
    if len(rows) != 1:
        raise DashboardError(f"聚合查询 {spec.name} 应返回 1 行，实际为 {len(rows)} 行")
    return rows[0]


def _capability(caps: Mapping[str, Any], key: str) -> bool:
    return bool(caps.get(key))


def _table_counts(runner: Any, caps: Mapping[str, Any]) -> tuple[dict[str, int], int | None]:
    specs = [TABLE_COUNTS_V1]
    if _capability(caps, "has_external_mapping"):
        specs.append(TABLE_COUNTS_V3)
    if _capability(caps, "has_deep_evidence") and _capability(caps, "has_deep_analysis"):
        specs.append(TABLE_COUNTS_V4)
    if all(
        _capability(caps, key)
        for key in (
            "has_user_behavior",
            "has_user_taste_profile",
            "has_user_food_history",
            "has_flavor_observation",
        )
    ):
        specs.append(TABLE_COUNTS_V6)
    if _capability(caps, "has_shadow_snapshot"):
        specs.append(TABLE_COUNTS_V9)
    if _capability(caps, "has_flyway_history"):
        # Flyway's own row count is already the installed migration count.
        specs.append(FLYWAY_VERSION)

    result: dict[str, int] = {}
    flyway_version: int | None = None
    for spec in specs:
        rows = runner.run(spec)
        if spec is FLYWAY_VERSION:
            flyway_version = int(rows[0]["flyway_version"] or 0)
            result["flyway_schema_history"] = int(rows[0]["migration_count"] or 0)
            continue
        for row in rows:
            result[str(row["table_name"])] = int(row["row_count"] or 0)
    return result, flyway_version


def _prefecture_index() -> PrefectureGridIndex:
    global _PREFECTURE_INDEX
    if _PREFECTURE_INDEX is None:
        try:
            _PREFECTURE_INDEX = PrefectureGridIndex.load()
        except ValueError as error:
            raise DashboardError("无法加载离线地级市索引") from error
    return _PREFECTURE_INDEX


def _location_payload(
    rows: Sequence[Mapping[str, Any]], overview: Mapping[str, Any], config: DashboardConfig
) -> dict[str, Any]:
    prefectures = _prefecture_index()
    grouped: dict[str, dict[str, Any]] = {}
    hidden: Mapping[str, Any] = {}
    unmatched_grid_count = 0
    unmatched_requests = 0
    unmatched_active_id_entries = 0
    unmatched_primary_ids = 0
    source_grid_count = 0
    for row in rows:
        if row.get("row_type") == "HIDDEN":
            hidden = row
            continue
        source_grid_count += 1
        latitude = float(row["latitude"])
        longitude = float(row["longitude"])
        primary_ids = int(row.get("primary_ids") or 0)
        active_ids = int(row.get("active_ids") or 0)
        requests = int(row.get("requests") or 0)
        prefecture = prefectures.lookup_gcj02(longitude, latitude)
        if prefecture is None:
            unmatched_grid_count += 1
            unmatched_requests += requests
            unmatched_active_id_entries += active_ids
            unmatched_primary_ids += primary_ids
            continue
        group = grouped.setdefault(
            prefecture.code,
            {
                "code": prefecture.code,
                "label": prefecture.label,
                "province": prefecture.province,
                "latitude": prefecture.latitude,
                "longitude": prefecture.longitude,
                "anonymousIds": 0,
                "activeIdEntries": 0,
                "requests": 0,
                "sourceGridCount": 0,
            },
        )
        group["anonymousIds"] += primary_ids
        group["activeIdEntries"] += active_ids
        group["requests"] += requests
        group["sourceGridCount"] += 1

    points = sorted(
        grouped.values(),
        key=lambda point: (-point["anonymousIds"], -point["requests"], point["label"]),
    )
    for point in points:
        point["lowSample"] = point["anonymousIds"] < config.low_sample_threshold

    mapped_primary_ids = sum(point["anonymousIds"] for point in points)
    mapped_requests = sum(point["requests"] for point in points)
    hidden_grid_count = int(hidden.get("hidden_grid_count") or 0)
    hidden_requests = int(hidden.get("hidden_requests") or 0)
    hidden_active_id_entries = int(hidden.get("hidden_active_id_entries") or 0)
    hidden_primary_ids = int(hidden.get("hidden_primary_ids") or 0)
    return {
        "coordinateSystem": "WGS-84",
        "sourceCoordinateSystem": "GCJ-02",
        "aggregationLevel": "prefecture",
        "gridPrecision": 1,
        "minimumAnonymousIds": config.min_map_users,
        "lowSampleThreshold": config.low_sample_threshold,
        "totalAnonymousIds": int(overview.get("total_anonymous_ids") or 0),
        "totalRequests": int(overview.get("total_recommendations") or 0),
        "points": points,
        "cityCount": len(points),
        "sourceGridCount": source_grid_count,
        "mappedAnonymousIds": mapped_primary_ids,
        "mappedRequests": mapped_requests,
        "locatedAnonymousIds": mapped_primary_ids + unmatched_primary_ids + hidden_primary_ids,
        "locatedRequests": mapped_requests + unmatched_requests + hidden_requests,
        "unmatchedGridCount": unmatched_grid_count,
        "unmatchedGridUserEntries": unmatched_active_id_entries,
        "unmatchedPrimaryIds": unmatched_primary_ids,
        "unmatchedGridRequests": unmatched_requests,
        "hiddenGridCount": hidden_grid_count,
        "hiddenGridUserEntries": hidden_active_id_entries,
        "hiddenPrimaryIds": hidden_primary_ids,
        "hiddenGridRequests": hidden_requests,
        "boundarySourceCommit": prefectures.source.get("commit"),
    }


def collect_snapshot(runner: Any, config: DashboardConfig) -> dict[str, Any]:
    """Collect a privacy-conscious snapshot made only of aggregate query results."""
    started = time.perf_counter()
    caps = _one(runner, CAPABILITIES)
    missing = [
        name
        for name, key in (
            ("recommendation_log", "has_recommendation_log"),
            ("restaurant", "has_restaurant"),
            ("user_feedback", "has_user_feedback"),
        )
        if not _capability(caps, key)
    ]
    if missing:
        raise DashboardError("数据库缺少 V1 核心表：" + "、".join(missing))

    meta = _one(runner, CONNECTION_META)
    overview = _one(runner, OVERVIEW, (config.days,))
    has_v6 = _capability(caps, "has_user_behavior") and _capability(caps, "has_selection_mode")
    has_v9 = _capability(caps, "has_shadow_snapshot")

    funnel = _one(runner, FUNNEL_V6 if has_v6 else FUNNEL_V1, (config.days,))
    daily = runner.run(DAILY_V6 if has_v6 else DAILY_V1, (config.days,))
    behaviors = runner.run(BEHAVIOR_DISTRIBUTION, (config.days,)) if has_v6 else []
    feedback = runner.run(FEEDBACK_DISTRIBUTION, (config.days,))
    risks = runner.run(RISK_DISTRIBUTION, (config.days,))
    risk_calibration = (
        runner.run(RISK_CALIBRATION)
        if _capability(caps, "has_risk_calibration_view")
        else []
    )
    categories = runner.run(
        CATEGORY_DISTRIBUTION, (config.days, config.max_categories)
    )
    algorithms = runner.run(
        ALGORITHM_DISTRIBUTION_V6 if has_v6 else ALGORITHM_DISTRIBUTION_V1,
        (config.days,),
    )
    location_rows = runner.run(
        LOCATION_GRIDS,
        (
            config.max_map_points,
            config.min_map_users,
            config.max_map_points,
            config.min_map_users,
        ),
    )
    table_rows, flyway_version = _table_counts(runner, caps)

    warnings: list[str] = []
    if not has_v6:
        warnings.append("当前数据库低于 V6：行为转化、选择模式和风险校准模块不可用。")
    if not has_v9:
        warnings.append("当前数据库没有 V9 shadow 快照表：实验覆盖模块不可用。")

    shadow: dict[str, Any]
    if has_v9:
        shadow = {
            "available": True,
            "summary": _camel_row(_one(runner, SHADOW_SUMMARY, (config.days,))),
            "variants": [_camel_row(row) for row in runner.run(SHADOW_VARIANTS, (config.days,))],
            "selectionReasons": [
                _camel_row(row)
                for row in runner.run(SHADOW_SELECTION_REASONS, (config.days,))
            ],
            "note": "覆盖率只表示 V9 快照落盘覆盖，不等同于 shadow 成功率。",
        }
    else:
        shadow = {
            "available": False,
            "summary": {},
            "variants": [],
            "selectionReasons": [],
            "note": "V9 不可用。",
        }

    snapshot_at = meta.get("snapshot_at")
    period_start = daily[0].get("metric_date") if daily else None
    period_end = daily[-1].get("metric_date") if daily else None
    query_count = getattr(runner, "query_count", None)
    elapsed_ms = round((time.perf_counter() - started) * 1000)

    payload = {
        "schemaVersion": 1,
        "meta": {
            "snapshotAt": _json_value(snapshot_at),
            "periodStart": _json_value(period_start),
            "periodEnd": _json_value(period_end),
            "windowDays": config.days,
            "databaseName": str(meta.get("database_name") or ""),
            "serverVersion": str(meta.get("server_version") or ""),
            "timezone": str(meta.get("timezone") or ""),
            "flywayVersion": flyway_version,
            "readOnlyVerified": str(meta.get("transaction_read_only", "")).lower()
            in {"on", "true", "1"},
            "sourceMode": "database",
            "sourceLabel": "PostgreSQL 只读聚合快照",
            "queryCount": query_count,
            "queryDurationMs": elapsed_ms,
            "warnings": warnings,
        },
        "overview": _camel_row(overview),
        "funnel": _camel_row(funnel),
        "daily": [_camel_row(row) for row in daily],
        "behaviors": [_camel_row(row) for row in behaviors],
        "feedback": [_camel_row(row) for row in feedback],
        "risks": [_camel_row(row) for row in risks],
        "riskCalibration": [_camel_row(row) for row in risk_calibration],
        "categories": [_camel_row(row) for row in categories],
        "algorithms": [_camel_row(row) for row in algorithms],
        "shadow": shadow,
        "locations": _location_payload(location_rows, overview, config),
        "tableRows": table_rows,
        "capabilities": {
            "behaviorMetrics": has_v6,
            "riskCalibration": bool(risk_calibration),
            "shadowSnapshots": has_v9,
        },
    }
    validate_snapshot(payload)
    return payload


def validate_snapshot(payload: Mapping[str, Any]) -> None:
    if not isinstance(payload.get("meta"), Mapping):
        raise DashboardError("快照缺少 meta 对象")
    if not isinstance(payload.get("overview"), Mapping):
        raise DashboardError("快照缺少 overview 对象")
    for key in ("daily", "behaviors", "feedback", "risks", "categories", "algorithms"):
        if not isinstance(payload.get(key), list):
            raise DashboardError(f"快照字段 {key} 必须是数组")
    serialized = json.dumps(_json_value(payload), ensure_ascii=False)
    if UUID_PATTERN.search(serialized):
        raise DashboardError("快照中发现完整 UUID，已拒绝写入 HTML")
    lowered = serialized.lower()
    for secret_key in ('"password"', '"dbpassword"', '"dsn"', '"databaseurl"'):
        if secret_key in lowered:
            raise DashboardError("快照中发现疑似数据库凭据字段，已拒绝写入 HTML")


def render_dashboard_html(template: str, payload: Mapping[str, Any]) -> str:
    validate_snapshot(payload)
    start = template.find(DATA_START)
    end = template.find(DATA_END)
    if start < 0 or end < 0 or end <= start:
        raise DashboardError("HTML 模板缺少唯一的数据岛标记")
    if template.find(DATA_START, start + len(DATA_START)) >= 0:
        raise DashboardError("HTML 模板包含重复的数据岛起始标记")
    json_payload = _safe_json_dumps(payload)
    block = (
        f"{DATA_START}\n"
        '<script id="elma-dashboard-data" type="application/json">\n'
        f"{json_payload}\n"
        "</script>\n"
        f"{DATA_END}"
    )
    rendered = template[:start] + block + template[end + len(DATA_END) :]
    validate_self_contained_html(rendered)
    return rendered


def validate_self_contained_html(html: str) -> None:
    checks = {
        "外部脚本": r"<script\b[^>]*\bsrc\s*=",
        "外部样式": r"<link\b[^>]*\brel\s*=\s*['\"]?stylesheet",
        "运行时 fetch": r"\bfetch\s*\(",
        "XMLHttpRequest": r"\bXMLHttpRequest\b",
        "WebSocket": r"\bWebSocket\b",
    }
    for label, pattern in checks.items():
        if re.search(pattern, html, flags=re.IGNORECASE):
            raise DashboardError(f"HTML 不是自包含文件：发现{label}")
    if html.count('id="elma-dashboard-data"') != 1:
        raise DashboardError("HTML 必须且只能包含一个运营数据岛")
    for required in (
        'id="overview-view"',
        'id="schema-view"',
        'id="catalog-view"',
        'id="locations-view"',
        'id="guide-view"',
        'id="queries-view"',
        "window.ELMA_CHINA_GEOJSON",
    ):
        if required not in html:
            raise DashboardError(f"HTML 缺少数据库导览模块：{required}")


def _atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="\n",
            prefix=f".{path.name}.",
            suffix=".tmp",
            dir=path.parent,
            delete=False,
        ) as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
            temporary = Path(handle.name)
        os.replace(temporary, path)
    finally:
        if temporary and temporary.exists():
            temporary.unlink()


def _load_fixture(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise DashboardError(f"无法读取 fixture：{exc}") from exc
    if not isinstance(payload, dict):
        raise DashboardError("fixture 顶层必须是 JSON 对象")
    validate_snapshot(payload)
    return payload


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="从 PostgreSQL 只读聚合数据，生成可直接双击打开的离线运营看板。"
    )
    parser.add_argument("--template", type=Path, default=DEFAULT_HTML, help="看板 HTML 底稿")
    parser.add_argument("--output", type=Path, default=DEFAULT_HTML, help="生成的 HTML 路径")
    parser.add_argument("--days", type=int, default=30, help="趋势和转化统计窗口，默认 30 天")
    parser.add_argument("--max-categories", type=int, default=8, help="品类榜单最大行数")
    parser.add_argument("--max-map-points", type=int, default=120, help="地图最大网格数")
    parser.add_argument("--min-map-users", type=int, default=1, help="地图网格最小活跃匿名标识数")
    parser.add_argument("--fixture", type=Path, help="仅用于开发验收的聚合快照 JSON；不连接数据库")
    parser.add_argument("--prompt-password", action="store_true", help="安全提示输入数据库密码，不写入命令历史")
    parser.add_argument("--check", action="store_true", help="只检查 SQL 注册表和 HTML 底稿")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    if not 1 <= args.days <= 366:
        raise DashboardError("--days 必须在 1 到 366 之间")
    if not 1 <= args.max_categories <= 20:
        raise DashboardError("--max-categories 必须在 1 到 20 之间")
    if not 1 <= args.max_map_points <= 200:
        raise DashboardError("--max-map-points 必须在 1 到 200 之间")
    if not 1 <= args.min_map_users <= 100:
        raise DashboardError("--min-map-users 必须在 1 到 100 之间")

    validate_registry()
    try:
        template = args.template.resolve().read_text(encoding="utf-8")
    except OSError as exc:
        raise DashboardError(f"无法读取 HTML 底稿：{exc}") from exc

    if args.check:
        validate_self_contained_html(template)
        print(f"DASHBOARD_CHECK_OK queries={len(__import__('dashboard_queries').ALL_QUERY_SPECS)}")
        return 0

    if args.fixture:
        payload = _load_fixture(args.fixture.resolve())
        mode = "fixture"
    else:
        config = DashboardConfig(
            days=args.days,
            max_categories=args.max_categories,
            max_map_points=args.max_map_points,
            min_map_users=args.min_map_users,
        )
        password = getpass.getpass("PostgreSQL password: ") if args.prompt_password else None
        with PostgresQueryRunner.connect_from_environment(password=password) as runner:
            password = None
            payload = collect_snapshot(runner, config)
        mode = "database"

    rendered = render_dashboard_html(template, payload)
    output = args.output.resolve()
    _atomic_write(output, rendered)
    meta = payload.get("meta", {})
    print(
        "DASHBOARD_OK "
        f"mode={mode} read_only={str(meta.get('readOnlyVerified', False)).lower()} "
        f"bytes={len(rendered.encode('utf-8'))} output={output}"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DashboardError as exc:
        print(f"DASHBOARD_ERROR {exc}", file=sys.stderr)
        raise SystemExit(2)
