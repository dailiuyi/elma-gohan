from __future__ import annotations

from http.client import HTTPConnection
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest


ROOT = Path(__file__).resolve().parents[2]
GUIDE_DIR = ROOT / "output" / "database-guide"
sys.path.insert(0, str(GUIDE_DIR))

import serve_dashboard as dashboard_server


class DashboardBridgeTest(unittest.TestCase):
    def setUp(self):
        self.template = (GUIDE_DIR / "index.html").read_text(encoding="utf-8")

    def test_static_template_stays_offline_and_hides_refresh_controls(self):
        self.assertIn("connect-src 'none'", self.template)
        self.assertIn('id="dashboard-refresh-controls" class="refresh-controls" hidden', self.template)
        self.assertNotIn(dashboard_server.BRIDGE_MARKER, self.template)
        self.assertNotRegex(self.template, r"\bfetch\s*\(")

    def test_served_page_gets_one_time_token_and_local_connection_policy(self):
        served = dashboard_server.inject_refresh_bridge(self.template, "test-token", 45)
        self.assertIn("connect-src 'self'", served)
        self.assertNotIn("connect-src 'none'", served)
        self.assertIn(dashboard_server.BRIDGE_MARKER, served)
        self.assertIn("test-token", served)
        self.assertIn("最近 45 天", served)
        self.assertRegex(served, r"\bfetch\s*\('/api/refresh'")
        self.assertNotIn("test-token", self.template)


class DashboardRefresherTest(unittest.TestCase):
    def test_refresh_uses_fixed_script_and_output_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "dashboard.html"
            calls = []

            def fake_runner(command, **kwargs):
                calls.append((command, kwargs))
                output.write_text("<!doctype html>", encoding="utf-8")
                return subprocess.CompletedProcess(command, 0, "DASHBOARD_OK read_only=true", "")

            result = dashboard_server.DashboardRefresher(output, 30, fake_runner).refresh()

            command, kwargs = calls[0]
            self.assertEqual(command[0], "powershell.exe")
            self.assertIn(str(dashboard_server.REFRESH_SCRIPT), command)
            self.assertEqual(command[command.index("-Days") + 1], "30")
            self.assertEqual(Path(command[command.index("-Output") + 1]), output.resolve())
            self.assertFalse(kwargs["check"])
            self.assertEqual(result.size, len("<!doctype html>"))

    def test_failed_refresh_returns_generic_error_and_keeps_old_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "dashboard.html"
            output.write_text("old dashboard", encoding="utf-8")

            def fake_runner(command, **kwargs):
                return subprocess.CompletedProcess(command, 1, "", "DB_PASSWORD=must-not-leak")

            refresher = dashboard_server.DashboardRefresher(output, 30, fake_runner)
            with self.assertRaisesRegex(dashboard_server.DashboardServerError, "生产刷新失败") as caught:
                refresher.refresh()

            self.assertNotIn("must-not-leak", str(caught.exception))
            self.assertEqual(output.read_text(encoding="utf-8"), "old dashboard")

    def test_concurrent_refresh_is_rejected(self):
        refresher = dashboard_server.DashboardRefresher(Path("dashboard.html"), 30)
        self.assertTrue(refresher._lock.acquire(blocking=False))
        try:
            with self.assertRaisesRegex(dashboard_server.DashboardServerError, "正在运行"):
                refresher.refresh()
        finally:
            refresher._lock.release()


class FakeRefresher:
    def __init__(self, output: Path):
        self.output = output
        self.calls = 0

    def refresh(self):
        self.calls += 1
        stat = self.output.stat()
        return dashboard_server.RefreshResult(self.output, stat.st_size, "2026-09-04T01:00:00+00:00")


class DashboardHttpServerTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.output = Path(self.temporary_directory.name) / "dashboard.html"
        self.output.write_text((GUIDE_DIR / "index.html").read_text(encoding="utf-8"), encoding="utf-8")
        self.application = dashboard_server.DashboardApplication(self.output, 30)
        self.fake_refresher = FakeRefresher(self.output)
        self.application.refresher = self.fake_refresher
        self.server = dashboard_server.DashboardHttpServer(("127.0.0.1", 0), self.application)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.port = self.server.server_address[1]

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temporary_directory.cleanup()

    def _post(self, token: str | None, origin: str | None = None):
        connection = HTTPConnection("127.0.0.1", self.port, timeout=2)
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["X-ELMA-Refresh-Token"] = token
        if origin is not None:
            headers["Origin"] = origin
        connection.request("POST", "/api/refresh", body="{}", headers=headers)
        response = connection.getresponse()
        payload = json.loads(response.read().decode("utf-8"))
        connection.close()
        return response.status, payload

    def test_dashboard_is_served_with_bridge(self):
        connection = HTTPConnection("127.0.0.1", self.port, timeout=2)
        connection.request("GET", "/")
        response = connection.getresponse()
        body = response.read().decode("utf-8")
        connection.close()

        self.assertEqual(response.status, 200)
        self.assertEqual(response.getheader("Cache-Control"), "no-store")
        self.assertIn(self.application.token, body)
        self.assertIn(dashboard_server.BRIDGE_MARKER, body)

    def test_refresh_requires_same_origin_and_one_time_token(self):
        allowed_origin = f"http://127.0.0.1:{self.port}"
        status, _ = self._post(self.application.token)
        self.assertEqual(status, 403)
        status, _ = self._post("wrong-token", allowed_origin)
        self.assertEqual(status, 403)
        status, payload = self._post(self.application.token, allowed_origin)
        self.assertEqual(status, 200)
        self.assertEqual(payload["message"], "生产数据刷新成功。")
        self.assertEqual(self.fake_refresher.calls, 1)


if __name__ == "__main__":
    unittest.main()
