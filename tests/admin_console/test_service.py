"""Contract, privacy, async failure isolation and persistence checks (no live DB)."""
from __future__ import annotations

import copy
from datetime import date
import http.client
import json
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "output" / "admin-console" / "server"))
from app import RefreshManager, SnapshotStore, ThreadingHTTPServer, handler_for, normalized_origin, validate_snapshot as api_validate_snapshot
from collector import CollectionError, collect, fixture_snapshot, location_payload, validate_range
from dashboard_queries import validate_read_only_query
from prefecture_lookup import PrefectureGridIndex
import queries


def sample() -> dict:
    return {"schemaVersion": 1, "meta": {"snapshotAt": "2026-09-07T10:00:00+08:00",
        "periodStart": "2026-09-01", "periodEnd": "2026-09-06", "windowDays": 6,
        "sourceMode": "fixture", "warnings": []}, "overview": {"periodRecommendations": 3},
        "daily": [], "behaviors": [], "feedback": [], "risks": [], "categories": [], "algorithms": []}


def slow_worker(send, start, end, fixture, budget):
    time.sleep(10)
    send.close()


class AggregateRunner:
    """Simulate an older V1 database; absent-table queries must never execute."""
    def __init__(self):
        self.query_count = 0

    def one(self, spec, params=()):
        return self.run(spec, params)[0]

    def run(self, spec, params=()):
        self.query_count += 1
        name = spec.name
        if name == "capabilities":
            return [{"has_recommendation_log": True, "has_restaurant": True, "has_user_feedback": True}]
        if name == "connection_meta":
            return [{"snapshot_at": "2026-09-07T10:00:00+08:00"}]
        if name == "console_overview":
            return [{"total_recommendations": 20, "total_anonymous_ids": 7,
                "total_restaurants": 30, "total_feedbacks": 3, "period_recommendations": 9,
                "period_active_ids": 4, "period_new_ids": 2, "period_returning_ids": 2,
                "average_candidate_count": 5}]
        if name == "console_funnel":
            return [{"recommendation_sessions": 9, "accepted_sessions": 0, "navigated_sessions": 0,
                "feedback_sessions": 2, "feedback_count": 3, "disliked_sessions": 1,
                "acceptance_rate": 0, "navigation_rate": 0, "feedback_rate": 2 / 9}]
        if name == "console_daily":
            return [{"metric_date": f"2026-09-{day:02d}", "active_ids": 2,
                "recommendations": 1, "new_ids": 0, "accepts": 0,
                "navigations": 0, "rerolls": 0, "feedbacks": 0, "dislikes": 0}
                for day in range(1, 7)]
        if name == "console_frequency":
            return [{"bucket": 1, "users": 4}]
        if name == "console_locations":
            return [{"code": "UNMAPPED", "requests": 9, "anonymous_ids": 4}]
        if name == "table_counts_v1":
            return [{"table_name": "recommendation_log", "row_count": 20}]
        if name in {"console_feedback", "console_risks", "console_categories",
                "console_algorithms", "console_heatmap", "console_retention"}:
            return []
        raise AssertionError("Unexpected unavailable query: " + name)


class DatesAndQueriesTests(unittest.TestCase):
    def test_range_inclusive_90_days(self):
        self.assertEqual(validate_range("2026-06-10", "2026-09-07", date(2026, 9, 7)),
            (date(2026, 6, 10), date(2026, 9, 7)))

    def test_invalid_ranges(self):
        for start, end in [("2026-06-09", "2026-09-07"), ("2026-09-08", "2026-09-08"),
            ("2026-09-07", "2026-09-06"), ("2026-9-1", "2026-09-06"),
            ("2026-02-30", "2026-03-01"), (None, "2026-09-01")]:
            with self.subTest(start=start, end=end), self.assertRaises(CollectionError):
                validate_range(start, end, date(2026, 9, 7))

    def test_sql_registry_read_only_and_bounded(self):
        for spec in queries.ALL_QUERIES:
            validate_read_only_query(spec)
            self.assertLessEqual(spec.max_rows, 500)

    def test_retention_excludes_incomplete_observation_days(self):
        for n in [1, 7, 14, 30]:
            self.assertIn(f"cohort_date + {n} < least((SELECT end_date FROM bounds), CURRENT_DATE)", queries.RETENTION.sql)

    def test_mapping_quality_does_not_conflate_absent_rating(self):
        sql = queries.MAPPING_STATUSES.sql
        self.assertIn("MATCHED_WITHOUT_RATING", sql)
        self.assertIn("MATCHED_WITH_RATING", sql)
        self.assertIn("ELSE match_status", sql)
        self.assertIn("AT TIME ZONE 'UTC'", queries.MAPPING_TOTALS.sql)

    def test_origin_validation(self):
        self.assertEqual(normalized_origin("https://elma-gohan.xyz/"),
            ("https://elma-gohan.xyz", "elma-gohan.xyz"))
        self.assertEqual(normalized_origin("http://localhost:5175"),
            ("http://localhost:5175", "localhost:5175"))
        for origin in ["https://user:pass@example.com", "https://example.com/path", "null", "ftp://example.com"]:
            with self.subTest(origin=origin), self.assertRaises(ValueError):
                normalized_origin(origin)

    def test_locations_output_only_city_centers_and_period_denominators(self):
        index = PrefectureGridIndex.load()
        city = index.cities[0]
        result = location_payload([{"code": city.code, "requests": 3, "anonymous_ids": 2},
            {"code": "UNMAPPED", "requests": 1, "anonymous_ids": 1}], index,
            {"periodActiveIds": 3, "periodRecommendations": 4})
        self.assertEqual(result["totalAnonymousIds"], 3)
        self.assertEqual(result["totalRequests"], 4)
        self.assertEqual(result["unmappedRequests"], 1)
        self.assertEqual(result["unmappedAnonymousIds"], 1)
        self.assertEqual(result["points"][0]["longitude"], city.longitude)
        self.assertTrue(result["points"][0]["lowSample"])
        self.assertEqual(sum(p["anonymousIds"] for p in result["points"]) + result["unmappedAnonymousIds"], 3)

    def test_missing_capabilities_are_null_not_zero(self):
        payload = collect(AggregateRunner(), date(2026, 9, 1), date(2026, 9, 6))
        self.assertFalse(payload["capabilities"]["behaviorMetrics"])
        self.assertFalse(payload["capabilities"]["quality"])
        for key in ("acceptedSessions", "navigatedSessions", "acceptanceRate", "navigationRate"):
            self.assertIsNone(payload["funnel"][key])
        for key in ("totalMappings", "mappingsWithRatings", "freshMappings"):
            self.assertIsNone(payload["analytics"]["quality"][key])
        for row in payload["daily"]:
            self.assertIsNone(row["accepts"])
            self.assertIsNone(row["navigations"])
            self.assertIsNone(row["rerolls"])
        self.assertEqual(payload["funnel"]["feedbackCount"], 3)

    def test_comparison_keeps_sql_cross_day_distinct_count(self):
        payload = collect(AggregateRunner(), date(2026, 9, 1), date(2026, 9, 6))
        comparison = payload["analytics"]["comparison"]
        self.assertEqual(comparison["current"]["activeIds"], 4)
        self.assertEqual(sum(row["activeIds"] for row in payload["daily"]), 12)
        self.assertEqual(comparison["previousFrom"], "2026-08-26")
        self.assertEqual(comparison["previousTo"], "2026-08-31")
        self.assertIsNone(comparison["previous"]["acceptedSessions"])

    def test_unavailable_average_candidate_count_stays_null(self):
        class EmptyPeriodRunner(AggregateRunner):
            def run(self, spec, params=()):
                rows = super().run(spec, params)
                if spec.name == "console_overview":
                    rows[0]["average_candidate_count"] = None
                    rows[0]["period_recommendations"] = 0
                return rows
        payload = collect(EmptyPeriodRunner(), date(2026, 9, 1), date(2026, 9, 6))
        self.assertEqual(payload["overview"]["periodRecommendations"], 0)
        self.assertIsNone(payload["overview"]["averageCandidateCount"])


class StorageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.store = SnapshotStore(self.directory)

    def tearDown(self):
        self.temp.cleanup()

    def test_keep_twelve_and_restore_after_restart(self):
        ids = [self.store.save(sample()) for _ in range(14)]
        self.assertEqual(len(self.store.history()), 12)
        self.assertEqual(len(list(self.directory.glob("*.json"))), 12)
        reloaded = SnapshotStore(self.directory)
        self.assertEqual(reloaded.current_id(), ids[-1])
        self.assertIsNone(reloaded.get(ids[0]))

    def test_atomic_failure_retains_current(self):
        identifier = self.store.save(sample())
        with patch("app.os.replace", side_effect=OSError("write denied")):
            with self.assertRaises(OSError):
                self.store.save(sample())
        self.assertEqual(self.store.current_id(), identifier)
        self.assertEqual(len(list(self.directory.glob("*.tmp"))), 0)

    def test_restart_ignores_non_object_json_and_preserves_valid_history(self):
        identifier = self.store.save(sample())
        for index, malformed in enumerate([[], None, "text", 1], 1):
            name = f"20260907T120000000000-{index:012x}.json"
            (self.directory / name).write_text(json.dumps(malformed), encoding="utf-8")
        reloaded = SnapshotStore(self.directory)
        self.assertEqual(reloaded.current_id(), identifier)
        self.assertEqual(len(reloaded.history()), 1)

    def test_non_object_snapshot_write_is_rejected_before_persistence(self):
        identifier = self.store.save(sample())
        for malformed in [[], None, "text", 1]:
            with self.subTest(value=malformed), self.assertRaises(CollectionError):
                self.store.save(malformed)
            with self.subTest(api_value=malformed), self.assertRaises(CollectionError):
                api_validate_snapshot(malformed)
        self.assertEqual(self.store.current_id(), identifier)
        self.assertEqual(len(list(self.directory.glob("*.json"))), 1)

    def test_raw_uuid_never_saved(self):
        payload = sample()
        payload["private"] = "7d205e2e-7f15-45fb-9a7a-10129949fb22"
        with self.assertRaises(RuntimeError):
            self.store.save(payload)
        self.assertEqual(self.store.history(), [])

    def test_fixture_never_relabels_date_or_claims_database(self):
        fixture = self.directory / "fixture.json"
        fixture.write_text(json.dumps(sample()), encoding="utf-8")
        result = fixture_snapshot(str(fixture), date(2026, 9, 1), date(2026, 9, 6))
        self.assertEqual(result["meta"]["sourceMode"], "fixture")
        self.assertFalse(result["meta"]["readOnlyVerified"])
        with self.assertRaises(CollectionError):
            fixture_snapshot(str(fixture), date(2026, 9, 2), date(2026, 9, 6))

    def test_timeout_and_mutex_retain_old_snapshot(self):
        identifier = self.store.save(sample())
        manager = RefreshManager(self.store, budget=0.2, worker=slow_worker)
        self.assertTrue(manager.start("2026-09-01", "2026-09-06"))
        self.assertFalse(manager.start("2026-09-01", "2026-09-06"))
        manager.thread.join(timeout=8)
        state = manager.state()
        self.assertFalse(state["refresh"]["running"])
        self.assertIn("180", state["refresh"]["error"])
        self.assertEqual(state["currentId"], identifier)


class HTTPTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.fixture = Path(self.temp.name) / "fixture.json"
        self.fixture.write_text(json.dumps(sample()), encoding="utf-8")
        self.store = SnapshotStore(Path(self.temp.name) / "snapshots")
        self.manager = RefreshManager(self.store, str(self.fixture), budget=10)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0),
            handler_for(self.manager, "https://elma-gohan.xyz"))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        if self.manager.thread:
            self.manager.thread.join(timeout=12)
        self.temp.cleanup()

    def request(self, method, path, body=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        default = {"Host": "elma-gohan.xyz"}
        if method == "POST":
            default.update({"Origin": "https://elma-gohan.xyz", "X-ELMA-CSRF": self.manager.token,
                "Content-Type": "application/json"})
        default.update(headers or {})
        connection.request(method, "/console/data/v1/" + path,
            json.dumps(body) if body is not None else None, default)
        response = connection.getresponse()
        result = (response.status, dict(response.getheaders()), json.loads(response.read()))
        connection.close()
        return result

    def test_state_and_empty_snapshot(self):
        status, headers, state = self.request("GET", "state")
        self.assertEqual(status, 200)
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertIsNone(state["currentId"])
        self.assertFalse(state["refresh"]["running"])
        self.assertEqual(self.request("GET", "snapshot")[0], 404)

    def test_host_origin_csrf_rejected(self):
        body = {"from": "2026-09-01", "to": "2026-09-06"}
        for headers in [{"Host": "evil.example"}, {"Origin": "https://evil.example"},
            {"X-ELMA-CSRF": "bad"}, {"Origin": "null"}]:
            with self.subTest(headers=headers):
                self.assertEqual(self.request("POST", "refresh", body, headers)[0], 403)
        self.assertEqual(self.request("GET", "state", headers={"Host": "127.0.0.1"})[0], 403)
        self.assertFalse(self.manager.state()["refresh"]["running"])

    def test_payload_and_date_validation(self):
        for body in [{"from": "2026-09-02", "to": "2026-09-01"},
            {"from": "2026-09-01", "to": "2026-09-06", "sql": "SELECT 1"}, []]:
            self.assertEqual(self.request("POST", "refresh", body)[0], 400)
        self.assertEqual(self.request("POST", "refresh", {}, {"Content-Type": "text/plain"})[0], 415)

    def test_snapshot_id_cannot_traverse_paths(self):
        for path in ["snapshot?id=../../fixture", "snapshot?id=", "snapshot?id=x&id=y", "snapshot?path=fixture"]:
            self.assertEqual(self.request("GET", path)[0], 400)

    def test_async_success_and_snapshot_history(self):
        status, _, _ = self.request("POST", "refresh", {"from": "2026-09-01", "to": "2026-09-06"})
        self.assertEqual(status, 202)
        self.manager.thread.join(timeout=12)
        _, _, state = self.request("GET", "state")
        self.assertFalse(state["refresh"]["running"])
        self.assertIsNone(state["refresh"]["error"])
        self.assertEqual(len(state["history"]), 1)
        status, _, snapshot = self.request("GET", "snapshot?id=" + state["currentId"])
        self.assertEqual(status, 200)
        self.assertEqual(snapshot["id"], state["currentId"])
        self.assertEqual(snapshot["meta"]["periodStart"], "2026-09-01")

    def test_failed_refresh_keeps_previous_snapshot(self):
        identifier = self.store.save(sample())
        status, _, _ = self.request("POST", "refresh", {"from": "2026-09-02", "to": "2026-09-06"})
        self.assertEqual(status, 202)
        self.manager.thread.join(timeout=12)
        state = self.manager.state()
        self.assertEqual(state["currentId"], identifier)
        self.assertIn("fixture", state["refresh"]["error"])


if __name__ == "__main__":
    unittest.main()
