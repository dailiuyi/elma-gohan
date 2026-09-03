from __future__ import annotations

from copy import deepcopy
from datetime import date, datetime, timezone
from decimal import Decimal
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
GUIDE_DIR = ROOT / "output" / "database-guide"
FIXTURE = Path(__file__).resolve().parent / "fixtures" / "sample_snapshot.json"


import sys

sys.path.insert(0, str(GUIDE_DIR))
import dashboard_queries as queries
import generate_dashboard as generator
from prefecture_lookup import PrefectureGridIndex


class DataIslandParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.collecting = False
        self.parts: list[str] = []

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == "script" and attributes.get("id") == "elma-dashboard-data":
            self.collecting = True

    def handle_endtag(self, tag):
        if tag == "script" and self.collecting:
            self.collecting = False

    def handle_data(self, data):
        if self.collecting:
            self.parts.append(data)


class QueryRegistryTest(unittest.TestCase):
    def test_all_registered_queries_are_bounded_and_read_only(self):
        queries.validate_registry()
        self.assertGreaterEqual(len(queries.ALL_QUERY_SPECS), 20)
        for query in queries.ALL_QUERY_SPECS:
            scrubbed = queries._scrub_sql(query.sql)
            self.assertNotRegex(scrubbed, r"(?i)\bselect\s+(?:\w+\.)?\s*\*")
            self.assertGreater(query.max_rows, 0)
            self.assertLessEqual(query.max_rows, 366)

    def test_count_star_is_allowed_but_raw_star_and_mutation_are_rejected(self):
        queries.validate_read_only_query(
            queries.QuerySpec("count", "SELECT count(*) AS total FROM recommendation_log", 1)
        )
        with self.assertRaisesRegex(ValueError, "SELECT \\*"):
            queries.validate_read_only_query(
                queries.QuerySpec("raw", "SELECT * FROM recommendation_log", 10)
            )
        with self.assertRaisesRegex(ValueError, "forbidden"):
            queries.validate_read_only_query(
                queries.QuerySpec("write", "UPDATE recommendation_log SET candidate_count = 0", 1)
            )


class PrefectureLookupTest(unittest.TestCase):
    def test_known_gcj02_grids_resolve_to_prefecture_names(self):
        index = PrefectureGridIndex.load()
        expected = {
            (112.9, 28.2): "长沙市",
            (113.3, 23.1): "广州市",
            (117.1, 39.2): "天津市",
            (116.4, 40.1): "北京市",
            (106.7, 26.7): "贵阳市",
            (114.3, 30.6): "武汉市",
        }
        for coordinates, label in expected.items():
            with self.subTest(coordinates=coordinates):
                prefecture = index.lookup_gcj02(*coordinates)
                self.assertIsNotNone(prefecture)
                self.assertEqual(prefecture.label, label)

    def test_location_payload_merges_adjacent_grids_by_prefecture(self):
        rows = [
            {"row_type": "POINT", "latitude": 28.2, "longitude": 112.9, "requests": 12, "active_ids": 5, "primary_ids": 4},
            {"row_type": "POINT", "latitude": 28.2, "longitude": 113.0, "requests": 4, "active_ids": 2, "primary_ids": 2},
            {"row_type": "POINT", "latitude": 23.1, "longitude": 113.3, "requests": 3, "active_ids": 1, "primary_ids": 1},
            {"row_type": "HIDDEN", "hidden_grid_count": 0, "hidden_requests": 0, "hidden_active_id_entries": 0, "hidden_primary_ids": 0},
        ]
        payload = generator._location_payload(
            rows,
            {"total_anonymous_ids": 7, "total_recommendations": 19},
            generator.DashboardConfig(),
        )
        self.assertEqual(payload["coordinateSystem"], "WGS-84")
        self.assertEqual(payload["aggregationLevel"], "prefecture")
        self.assertEqual(payload["cityCount"], 2)
        self.assertEqual(payload["sourceGridCount"], 3)
        self.assertEqual([point["label"] for point in payload["points"]], ["长沙市", "广州市"])
        changsha = payload["points"][0]
        self.assertEqual(changsha["anonymousIds"], 6)
        self.assertEqual(changsha["requests"], 16)
        self.assertEqual(changsha["sourceGridCount"], 2)
        self.assertNotIn("网格", json.dumps(payload, ensure_ascii=False))


class RendererTest(unittest.TestCase):
    def setUp(self):
        self.template = (GUIDE_DIR / "index.html").read_text(encoding="utf-8")
        self.snapshot = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def test_generated_html_is_self_contained_and_keeps_database_guide(self):
        rendered = generator.render_dashboard_html(self.template, self.snapshot)
        generator.validate_self_contained_html(rendered)
        for view in ("schema", "catalog", "locations", "guide", "queries"):
            self.assertIn(f'id="{view}-view"', rendered)
            self.assertIn(f'data-view="{view}"', rendered)
        self.assertIn("ELMA Gohan 数据库表关系图", rendered)
        self.assertIn("人工连接", rendered)
        self.assertIn("常用查询", rendered)
        self.assertNotRegex(rendered, r"(?i)<script\b[^>]*\bsrc\s*=")
        self.assertNotRegex(rendered, r"(?i)<link\b[^>]*stylesheet")
        self.assertNotRegex(rendered, r"(?i)\bfetch\s*\(")

    def test_json_is_script_safe_and_round_trips_hostile_text(self):
        hostile = deepcopy(self.snapshot)
        payload_text = '</script><script>globalThis.__pwned=1</script><img src=x onerror="boom">&\u2028\u2029'
        hostile["categories"][0]["label"] = payload_text
        hostile["meta"]["warnings"] = [payload_text]
        rendered = generator.render_dashboard_html(self.template, hostile)
        self.assertNotIn("</script><script>globalThis.__pwned", rendered)
        self.assertIn("\\u003c/script\\u003e", rendered)
        self.assertEqual(rendered.count("<script"), self.template.count("<script"))

        parser = DataIslandParser()
        parser.feed(rendered)
        decoded = json.loads("".join(parser.parts))
        self.assertEqual(decoded["categories"][0]["label"], payload_text)
        self.assertEqual(decoded["meta"]["warnings"][0], payload_text)

    def test_full_uuid_and_secret_fields_are_rejected(self):
        uuid_payload = deepcopy(self.snapshot)
        uuid_payload["meta"]["warning"] = "123e4567-e89b-42d3-a456-426614174000"
        with self.assertRaisesRegex(generator.DashboardError, "完整 UUID"):
            generator.validate_snapshot(uuid_payload)
        secret_payload = deepcopy(self.snapshot)
        secret_payload["meta"]["password"] = "should-not-appear"
        with self.assertRaisesRegex(generator.DashboardError, "凭据"):
            generator.validate_snapshot(secret_payload)

    def test_fixture_cli_writes_atomically_to_another_file(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "dashboard.html"
            exit_code = generator.main(
                [
                    "--template",
                    str(GUIDE_DIR / "index.html"),
                    "--fixture",
                    str(FIXTURE),
                    "--output",
                    str(output),
                ]
            )
            self.assertEqual(exit_code, 0)
            generated = output.read_text(encoding="utf-8")
            self.assertIn("本地验收聚合快照", generated)
            self.assertNotIn("123e4567-e89b", generated)
            self.assertFalse(any(output.parent.glob(f".{output.name}.*.tmp")))


class FakeRunner:
    def __init__(self, results):
        self.results = results
        self.calls: list[str] = []
        self.query_count = 0

    def run(self, spec, params=()):
        self.calls.append(spec.name)
        self.query_count += 1
        return deepcopy(self.results[spec.name])


class CollectorTest(unittest.TestCase):
    def test_v1_empty_database_stays_valid_and_marks_optional_modules(self):
        results = {
            "capabilities": [{
                "has_recommendation_log": True,
                "has_restaurant": True,
                "has_user_feedback": True,
                "has_user_behavior": False,
                "has_user_taste_profile": False,
                "has_user_food_history": False,
                "has_flavor_observation": False,
                "has_external_mapping": False,
                "has_deep_evidence": False,
                "has_deep_analysis": False,
                "has_shadow_snapshot": False,
                "has_flyway_history": False,
                "has_recommendation_metrics_view": False,
                "has_risk_calibration_view": False,
                "has_selection_mode": False,
            }],
            "connection_meta": [{
                "database_name": "empty_test",
                "server_version": "17.6",
                "timezone": "Asia/Shanghai",
                "transaction_read_only": "on",
                "snapshot_at": datetime(2026, 9, 3, 20, 0, tzinfo=timezone.utc),
            }],
            "overview": [{
                "total_recommendations": 0,
                "total_anonymous_ids": 0,
                "total_restaurants": 0,
                "total_feedbacks": 0,
                "total_returning_ids": 0,
                "total_returning_rate": None,
                "average_requests_per_id": None,
                "average_candidate_count": None,
                "first_request_at": None,
                "last_request_at": None,
                "period_recommendations": 0,
                "period_active_ids": 0,
                "period_new_ids": 0,
                "period_returning_ids": 0,
            }],
            "funnel_v1": [{
                "recommendation_sessions": 0,
                "feedback_sessions": 0,
                "feedback_count": Decimal("0"),
                "disliked_sessions": 0,
                "feedback_rate": None,
            }],
            "daily_v1": [{
                "metric_date": date(2026, 9, 3),
                "recommendations": 0,
                "active_ids": 0,
                "new_ids": 0,
                "accepts": 0,
                "navigations": 0,
                "rerolls": 0,
                "feedbacks": 0,
                "dislikes": 0,
            }],
            "feedback_distribution": [],
            "risk_distribution": [],
            "category_distribution": [],
            "algorithm_distribution_v1": [],
            "location_grids": [{
                "row_type": "HIDDEN",
                "latitude": None,
                "longitude": None,
                "requests": 0,
                "active_ids": 0,
                "primary_ids": 0,
                "hidden_grid_count": 0,
                "hidden_requests": 0,
                "hidden_active_id_entries": 0,
                "hidden_primary_ids": 0,
            }],
            "table_counts_v1": [
                {"table_name": name, "row_count": 0}
                for name in (
                    "restaurant",
                    "risk_result",
                    "recommendation_log",
                    "recommendation_candidate",
                    "user_feedback",
                    "user_preference",
                )
            ],
        }
        runner = FakeRunner(results)
        snapshot = generator.collect_snapshot(runner, generator.DashboardConfig(days=1))
        self.assertEqual(snapshot["overview"]["totalRecommendations"], 0)
        self.assertEqual(snapshot["locations"]["points"], [])
        self.assertFalse(snapshot["shadow"]["available"])
        self.assertFalse(snapshot["capabilities"]["behaviorMetrics"])
        self.assertTrue(any("V6" in warning for warning in snapshot["meta"]["warnings"]))
        self.assertNotIn("behavior_distribution", runner.calls)
        self.assertNotIn("shadow_summary", runner.calls)

    def test_query_runner_rejects_oversized_result(self):
        class Cursor:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return None

            def execute(self, *_):
                return None

            def fetchmany(self, _):
                return [{"total": 1}, {"total": 2}]

        class Connection:
            def cursor(self):
                return Cursor()

        runner = generator.PostgresQueryRunner(Connection())
        spec = queries.QuerySpec("bounded", "SELECT count(*) AS total FROM recommendation_log", 1)
        with self.assertRaisesRegex(generator.DashboardError, "超过 1 行"):
            runner.run(spec)


if __name__ == "__main__":
    unittest.main()
