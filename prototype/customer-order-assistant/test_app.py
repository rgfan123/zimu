import json
import threading
import unittest
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib import error, request

from app import ApiError, AssistantService, Config, Handler, OrderApiClient, SessionStore


class FakeExtractor:
    def extract(self, draft, messages):
        return {
            "draft_patch": {
                "customer": {"customer_name": "上海子牧团餐", "customer_code": None},
                "receiver": {
                    "receiver_name": "李经理",
                    "receiver_phone": "13800000001",
                    "address": "上海市浦东新区演示路 8 号",
                },
                "required_delivery_time": "2026-08-15T16:00:00+08:00",
                "items": [{
                    "product_name": "子牧羊小腿",
                    "sku_code": None,
                    "specification": "500g/盒",
                    "quantity": 2,
                    "unit": "盒",
                }],
                "settlement": {
                    "settlement_method": "月结",
                    "settlement_time": "2026-08-31T18:00:00+08:00",
                },
                "remark": "前台人工核对",
            },
            "missing_fields": [],
            "next_question": "",
            "ready_to_confirm": True,
        }


class FakeInsightAgent:
    def analyze(self, draft, messages):
        return {
            "profile_summary": "企业团餐客户",
            "preference_summary": "按盒采购",
            "profile_tags": ["BULK_PURCHASER"],
            "preference_tags": ["BULK_PACKAGING"],
            "recommended_categories": [],
        }


class FakeDemoOrderApi:
    def __init__(self):
        self.submissions = []

    def submit(self, draft, idempotency_key):
        self.submissions.append({"draft": draft, "idempotency_key": idempotency_key})
        return {
            "id": "91",
            "run_no": "RUN-AI-TEST",
            "scenario_code": "AI_EXTRACTED_ORDER",
            "status": "SUCCEEDED",
            "data_scope": "DEMO",
            "order_id": "101",
            "order": {"order_no": "DEMO-ORD-AI-TEST"},
            "timeline": [],
            "started_at": "2026-08-12T13:00:00+08:00",
            "finished_at": "2026-08-12T13:00:01+08:00",
        }


class CapturingOrderApiHandler(BaseHTTPRequestHandler):
    captured_headers = None
    captured_body = None

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        type(self).captured_headers = self.headers
        type(self).captured_body = json.loads(self.rfile.read(length).decode("utf-8"))
        payload = json.dumps({"id": "demo-run-1", "data_scope": "DEMO"}).encode("utf-8")
        self.send_response(201)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format, *args):
        return


class OrderApiClientAuthTest(unittest.TestCase):
    def setUp(self):
        CapturingOrderApiHandler.captured_headers = None
        CapturingOrderApiHandler.captured_body = None
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), CapturingOrderApiHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def test_internal_service_identity_overwrites_untrusted_extra_headers(self):
        config = replace(
            Config.from_env(),
            order_api_base_url=self.base_url,
            order_api_path="/demo/v1/extracted-orders",
            order_api_service_name="trusted-order-assistant",
            order_api_bearer_token="internal-service-token",
            order_api_extra_headers={
                "authorization": "Basic forged-admin-secret",
                "x-OPERATOR": "forged-browser-operator",
                "X-Trace": "trace-1",
            },
        )

        result = OrderApiClient(config).submit({"confirmed": True}, "demo-order-auth-001")

        self.assertEqual(result["data_scope"], "DEMO")
        self.assertEqual(
            CapturingOrderApiHandler.captured_headers.get("Authorization"),
            "Bearer internal-service-token",
        )
        self.assertEqual(
            CapturingOrderApiHandler.captured_headers.get_all("Authorization"),
            ["Bearer internal-service-token"],
        )
        self.assertEqual(
            CapturingOrderApiHandler.captured_headers.get("X-Operator"),
            "trusted-order-assistant",
        )
        self.assertEqual(
            CapturingOrderApiHandler.captured_headers.get_all("X-Operator"),
            ["trusted-order-assistant"],
        )
        self.assertEqual(CapturingOrderApiHandler.captured_headers.get("X-Trace"), "trace-1")
        self.assertEqual(
            CapturingOrderApiHandler.captured_headers.get("Idempotency-Key"),
            "demo-order-auth-001",
        )

    def test_missing_internal_service_identity_fails_before_network(self):
        config = replace(
            Config.from_env(),
            order_api_base_url=self.base_url,
            order_api_service_name="",
            order_api_bearer_token="",
        )

        with self.assertRaises(ApiError) as raised:
            OrderApiClient(config).submit({"confirmed": True}, "demo-order-auth-002")

        self.assertEqual(raised.exception.status, 503)
        self.assertEqual(raised.exception.code, "ORDER_API_INTERNAL_AUTH_REQUIRED")
        self.assertIsNone(CapturingOrderApiHandler.captured_headers)


class OrderAssistantHttpTest(unittest.TestCase):
    def setUp(self):
        config = replace(
            Config.from_env(),
            llm_model="fake-model",
            insight_model="fake-model",
            builtin_order_api_enabled=False,
            order_api_path="/demo/v1/extracted-orders",
        )
        store = SessionStore()
        Handler.config = config
        Handler.store = store
        self.order_api = FakeDemoOrderApi()
        Handler.assistant = AssistantService(
            store,
            FakeExtractor(),
            FakeInsightAgent(),
            self.order_api,
        )
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request_json(self, path, method="GET", body=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = request.Request(
            self.base_url + path,
            data=data,
            method=method,
            headers={"Content-Type": "application/json"},
        )
        with request.urlopen(req, timeout=2) as response:
            return response.status, json.loads(response.read().decode("utf-8"))

    def test_http_session_extracts_then_confirms_a_demo_run(self):
        status, config = self.request_json("/customer/v1/order-assistant/config")
        self.assertEqual(status, 200)
        self.assertTrue(config["service_ready"])
        self.assertTrue(config["demo_mode"])

        status, session = self.request_json(
            "/customer/v1/order-assistant/sessions", method="POST", body={}
        )
        self.assertEqual(status, 201)
        status, session = self.request_json(
            f"/customer/v1/order-assistant/sessions/{session['session_id']}/messages",
            method="POST",
            body={"message": "上海子牧团餐要两盒羊小腿，送到演示路 8 号"},
        )
        self.assertEqual(status, 200)
        self.assertEqual(session["status"], "READY_TO_CONFIRM")
        self.assertEqual(session["missing_fields"], [])

        status, confirmed = self.request_json(
            f"/customer/v1/order-assistant/sessions/{session['session_id']}/confirm",
            method="POST",
            body={},
        )
        self.assertEqual(status, 200)
        self.assertEqual(confirmed["status"], "CONFIRMED")
        self.assertEqual(confirmed["order_result"]["data_scope"], "DEMO")
        self.assertEqual(confirmed["order_result"]["scenario_code"], "AI_EXTRACTED_ORDER")
        self.assertEqual(len(self.order_api.submissions), 1)
        self.assertIs(self.order_api.submissions[0]["draft"]["confirmed"], True)

    def test_incomplete_session_cannot_submit_an_order(self):
        status, session = self.request_json(
            "/customer/v1/order-assistant/sessions", method="POST", body={}
        )
        self.assertEqual(status, 201)

        with self.assertRaises(error.HTTPError) as raised:
            self.request_json(
                f"/customer/v1/order-assistant/sessions/{session['session_id']}/confirm",
                method="POST",
                body={},
            )

        self.assertEqual(raised.exception.code, 422)
        with raised.exception as response:
            payload = json.loads(response.read().decode("utf-8"))
        self.assertEqual(payload["business_code"], "ORDER_DRAFT_INCOMPLETE")
        self.assertEqual(self.order_api.submissions, [])


if __name__ == "__main__":
    unittest.main()
