"""Aggregate-only snapshot collection with a read-only PostgreSQL transaction."""
from __future__ import annotations

import copy
from datetime import date, datetime, timedelta, timezone
import json
from pathlib import Path
import re
import time
from typing import Any

import queries as q
from dashboard_queries import (CAPABILITIES, CONNECTION_META, EVIDENCE_RELIABILITY,
    SHADOW_SUMMARY, SHADOW_VARIANTS, SHADOW_SELECTION_REASONS, validate_read_only_query)
from generate_dashboard import _camel_row, _json_value, _table_counts, validate_snapshot as _legacy_validate_snapshot
from prefecture_lookup import PrefectureGridIndex

SHANGHAI = timezone(timedelta(hours=8), "Asia/Shanghai")


class CollectionError(RuntimeError):
    """A credential-free, user-visible collection failure."""


def validate_snapshot(payload: Any) -> None:
    if not isinstance(payload, dict):
        raise CollectionError("快照顶层必须是 JSON 对象。")
    _legacy_validate_snapshot(payload)


def now_iso() -> str:
    return datetime.now(SHANGHAI).isoformat(timespec="seconds")


def validate_range(start: Any, end: Any, today: date | None = None) -> tuple[date, date]:
    if not isinstance(start, str) or not isinstance(end, str):
        raise CollectionError("请选择开始和结束日期。")
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", start) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", end):
        raise CollectionError("日期必须为 YYYY-MM-DD。")
    try:
        first, last = date.fromisoformat(start), date.fromisoformat(end)
    except ValueError as exc:
        raise CollectionError("日期无效。") from exc
    today = today or datetime.now(SHANGHAI).date()
    if first < date(2000, 1, 1) or last < first or (last - first).days >= 90 or last > today:
        raise CollectionError("统计区间须为 1–90 天，不能晚于上海当前日期。")
    return first, last


class QueryRunner:
    def __init__(self, connection: Any, deadline: float):
        self.connection = connection
        self.deadline = deadline
        self.query_count = 0

    @classmethod
    def connect(cls, deadline: float) -> "QueryRunner":
        try:
            import psycopg
            from psycopg.rows import dict_row
            # Empty conninfo deliberately uses PG* only. No DATABASE_URL or URL interpolation.
            connection = psycopg.connect("", row_factory=dict_row, connect_timeout=10,
                application_name="elma-admin-console", options=(
                    "-c default_transaction_read_only=on -c timezone=Asia/Shanghai "
                    "-c statement_timeout=15000 -c lock_timeout=3000 "
                    "-c idle_in_transaction_session_timeout=20000"))
            connection.read_only = True
            connection.isolation_level = psycopg.IsolationLevel.REPEATABLE_READ
            runner = cls(connection, deadline)
            meta = runner.one(CONNECTION_META)
            if meta["transaction_read_only"] != "on" or meta["timezone"] != "Asia/Shanghai":
                connection.close()
                raise CollectionError("数据库未确认只读或上海时区，已取消刷新。")
            return runner
        except CollectionError:
            raise
        except Exception as exc:
            # Database exceptions can include connection strings, addresses or raw SQL.
            raise CollectionError("数据库连接失败，请检查数据服务的数据库配置。") from exc

    def run(self, spec: Any, params: Any = ()) -> list[dict[str, Any]]:
        validate_read_only_query(spec)
        if time.monotonic() >= self.deadline:
            raise CollectionError("刷新超过 180 秒预算，请缩短区间后重试。")
        try:
            with self.connection.cursor() as cursor:
                cursor.execute(spec.sql, tuple(params))
                rows = cursor.fetchmany(spec.max_rows + 1)
            if len(rows) > spec.max_rows:
                raise CollectionError(f"聚合 {spec.name} 超过行数上限，已保留原快照。")
            self.query_count += 1
            return [dict(row) for row in rows]
        except CollectionError:
            raise
        except Exception as exc:
            raise CollectionError(f"聚合 {spec.name} 未完成，请稍后重试。") from exc

    def one(self, spec: Any, params: Any = ()) -> dict[str, Any]:
        rows = self.run(spec, params)
        if len(rows) != 1:
            raise CollectionError(f"聚合 {spec.name} 未返回预期统计。")
        return rows[0]

    def close(self) -> None:
        try:
            self.connection.rollback()
        finally:
            self.connection.close()


def location_payload(rows: list[dict[str, Any]], index: PrefectureGridIndex,
                     overview: dict[str, Any]) -> dict[str, Any]:
    cities = {city.code: city for city in index.cities}
    points = []
    unmapped_requests = unmapped_ids = 0
    for row in rows:
        city = cities.get(row["code"])
        if city is None:
            unmapped_requests += int(row["requests"])
            unmapped_ids += int(row["anonymous_ids"])
            continue
        ids = int(row["anonymous_ids"])
        points.append({"code": city.code, "label": city.label, "province": city.province,
            "longitude": city.longitude, "latitude": city.latitude,
            "anonymousIds": ids, "requests": int(row["requests"]), "lowSample": ids < 3})
    return {"totalAnonymousIds": int(overview["periodActiveIds"]),
        "totalRequests": int(overview["periodRecommendations"]), "points": points,
        "unmappedRequests": unmapped_requests, "unmappedAnonymousIds": unmapped_ids,
        "coordinateSystem": "WGS-84", "sourceCoordinateSystem": "GCJ-02",
        "aggregationLevel": "prefecture", "boundarySourceCommit": index.source.get("commit"),
        "note": "按所选区间统计；先将 GCJ-02 坐标四舍五入到 0.1 度网格，再归属地级市，边界附近可能偏差。气泡位于行政区中心。每个匿名标识归属请求最多的城市（含未归属），并列按最近访问；不代表自然人数。"}


def collect(runner: Any, start: date, end: date) -> dict[str, Any]:
    started = time.monotonic()
    params = (start, end)
    caps = runner.one(CAPABILITIES)
    if not all(caps.get(key) for key in ("has_recommendation_log", "has_restaurant", "has_user_feedback")):
        raise CollectionError("缺少推荐、餐厅或反馈核心表，无法生成统计。")
    meta = runner.one(CONNECTION_META)
    behavior = bool(caps.get("has_user_behavior"))
    overview = _camel_row(runner.one(q.OVERVIEW, params))
    funnel = _camel_row(runner.one(q.funnel(behavior), params))
    days = (end - start).days + 1
    previous_end = start - timedelta(days=1)
    previous_start = start - timedelta(days=days)
    previous_overview = _camel_row(runner.one(q.OVERVIEW, (previous_start, previous_end)))
    previous_funnel = _camel_row(runner.one(q.funnel(behavior), (previous_start, previous_end)))
    if not behavior:
        for metrics in (funnel, previous_funnel):
            for key in ("acceptedSessions", "navigatedSessions", "acceptanceRate", "navigationRate"):
                metrics[key] = None

    def comparison(o: dict, f: dict) -> dict:
        return {"requests": o["periodRecommendations"], "activeIds": o["periodActiveIds"],
            "newIds": o["periodNewIds"], "acceptedSessions": f["acceptedSessions"],
            "acceptanceRate": f["acceptanceRate"], "feedbackRate": f["feedbackRate"]}

    frequency_rows = {int(row["bucket"]): int(row["users"]) for row in runner.run(q.FREQUENCY, params)}
    index = PrefectureGridIndex.load()
    segments = [{"lat": lat, "lo": lo, "hi": hi, "code": index.cities[city].code}
        for lat, row in index.rows.items() for lo, hi, city in row]
    locations = location_payload(runner.run(q.LOCATIONS, (*params, json.dumps(segments))), index, overview)
    warnings = ["匿名标识是设备/浏览器标识，不等于自然人数。当前日期的统计尚未结束；留存只计完整观察日。",
        "首访/新增以当前保留推荐记录中的最早请求为准，不能识别记录保留期之前的首次使用。",
        "累计规模、表行数、证据质量和队列状态是刷新时的存量；其余指标按所选区间。",
        "漏斗按区间内推荐会话及截至结束日的关联行为/反馈去重；日趋势按事件发生日，二者口径不同。"]
    if not behavior:
        warnings.append("数据库没有行为表；接受、导航、换一批指标不可用，以空值返回。")
    quality = {"mappingStatuses": [], "deepStatuses": [], "totalMappings": None,
        "mappingsWithRatings": None, "freshMappings": None}
    if caps.get("has_external_mapping"):
        quality.update(_camel_row(runner.one(q.MAPPING_TOTALS)))
        quality["mappingStatuses"] = [_camel_row(row) for row in runner.run(q.MAPPING_STATUSES)]
    else:
        warnings.append("跨平台映射表不可用。")
    if caps.get("has_deep_evidence"):
        quality["deepStatuses"] = [_camel_row(row) for row in runner.run(q.DEEP_STATUSES)]
    else:
        warnings.append("深度证据表不可用。")
    quality["note"] = "刷新时数据库存量，非历史区间快照；匹配与评分覆盖率不等于人工核验准确率。"
    shadow = {"available": False, "summary": {}, "variants": [], "selectionReasons": [],
        "note": "当前数据库无 Shadow 快照能力。"}
    if caps.get("has_shadow_snapshot"):
        shadow = {"available": True,
            "summary": _camel_row(runner.one(q.scoped_shadow(SHADOW_SUMMARY), params)),
            "variants": [_camel_row(row) for row in runner.run(q.scoped_shadow(SHADOW_VARIANTS), params)],
            "selectionReasons": [_camel_row(row) for row in runner.run(q.scoped_shadow(SHADOW_SELECTION_REASONS), params)],
            "note": "快照覆盖和首选变化仅为实验可观测性，不代表算法质量提升。"}
    else:
        warnings.append("Shadow 快照表不可用。")
    table_rows, flyway_version = _table_counts(runner, caps)
    daily = [_camel_row(row) for row in runner.run(q.daily(behavior), params)]
    if not behavior:
        for row in daily:
            for key in ("accepts", "navigations", "rerolls"):
                row[key] = None
    payload = {"schemaVersion": 1, "meta": {"snapshotAt": _json_value(meta["snapshot_at"]),
        "periodStart": start.isoformat(), "periodEnd": end.isoformat(), "windowDays": days,
        "timezone": "Asia/Shanghai", "sourceMode": "database", "readOnlyVerified": True,
        "flywayVersion": flyway_version, "warnings": warnings},
        "overview": overview, "funnel": funnel,
        "daily": daily,
        "behaviors": [_camel_row(row) for row in runner.run(q.BEHAVIORS, params)] if behavior else [],
        "feedback": [_camel_row(row) for row in runner.run(q.FEEDBACK, params)],
        "risks": [_camel_row(row) for row in runner.run(q.RISKS, params)],
        "categories": [_camel_row(row) for row in runner.run(q.CATEGORIES, params)],
        "algorithms": [_camel_row(row) for row in runner.run(q.algorithms(bool(caps.get("has_selection_mode"))), params)],
        "evidenceReliability": _camel_row(runner.one(EVIDENCE_RELIABILITY)) if caps.get("has_baidu_enrichment") else {},
        "locations": locations, "shadow": shadow, "tableRows": table_rows,
        "capabilities": {"behaviorMetrics": behavior, "shadowSnapshots": bool(caps.get("has_shadow_snapshot")),
            "retention": True, "quality": bool(caps.get("has_external_mapping")),
            "deepEvidence": bool(caps.get("has_deep_evidence")),
            "evidenceReliability": bool(caps.get("has_baidu_enrichment")), "manualRefresh": True},
        "analytics": {"comparison": {"previousFrom": previous_start.isoformat(), "previousTo": previous_end.isoformat(),
            "current": comparison(overview, funnel), "previous": comparison(previous_overview, previous_funnel)},
            "frequency": [{"label": label, "users": frequency_rows.get(i, 0)}
                for i, label in enumerate(("1次", "2–3次", "4–7次", "8次及以上"), 1)],
            "heatmap": [_camel_row(row) for row in runner.run(q.HEATMAP, params)],
            "retention": [_camel_row(row) for row in runner.run(q.RETENTION, params)], "quality": quality}}
    payload["meta"].update(queryCount=runner.query_count, queryDurationMs=round((time.monotonic() - started) * 1000))
    validate_snapshot(payload)
    return payload


def fixture_snapshot(path: str, start: date, end: date) -> dict[str, Any]:
    try:
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
        validate_snapshot(payload)
    except Exception as exc:
        raise CollectionError("无法读取聚合 fixture。") from exc
    meta = payload["meta"]
    if meta.get("periodStart") != start.isoformat() or meta.get("periodEnd") != end.isoformat():
        raise CollectionError("fixture 仅支持其原始日期区间，不能重算其他日期。")
    payload = copy.deepcopy(payload)
    payload["meta"].update(sourceMode="fixture", readOnlyVerified=False)
    payload["meta"].setdefault("warnings", []).append("开发 fixture，仅为保存的聚合样本，不代表生产实时数据。")
    return payload


def collect_worker(send: Any, start: str, end: str, fixture: str | None, budget: float) -> None:
    runner = None
    try:
        first, last = validate_range(start, end)
        if fixture:
            result = fixture_snapshot(fixture, first, last)
        else:
            runner = QueryRunner.connect(time.monotonic() + budget)
            result = collect(runner, first, last)
        send.send({"ok": True, "snapshot": result})
    except CollectionError as exc:
        send.send({"ok": False, "error": str(exc)})
    except Exception:
        send.send({"ok": False, "error": "统计刷新失败，原快照已保留。请检查服务日志或稍后重试。"})
    finally:
        if runner:
            runner.close()
        send.close()
