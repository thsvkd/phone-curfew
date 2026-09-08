#!/usr/bin/env python3
"""Draft review server: static files + one feedback.json endpoint."""
import http.server
import json
import pathlib
import socketserver
import sys

ROOT = pathlib.Path(__file__).resolve().parent
FEEDBACK = ROOT / "feedback.json"
DOCS = {"/PRD.md": ROOT.parent / "PRD.md", "/SPEC.md": ROOT.parent / "SPEC.md"}
MAX_BODY = 1_000_000


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(ROOT), **kw)

    def _json(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        doc = DOCS.get(self.path)
        if doc is not None:
            body = doc.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/markdown; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path == "/feedback":
            if FEEDBACK.exists():
                self._json(200, json.loads(FEEDBACK.read_text(encoding="utf-8")))
            else:
                self._json(200, {})
            return
        super().do_GET()

    def do_POST(self):
        if self.path != "/feedback":
            self.send_error(404)
            return
        size = int(self.headers.get("Content-Length") or 0)
        if size <= 0 or size > MAX_BODY:
            self.send_error(413, "body too large or empty")
            return
        try:
            data = json.loads(self.rfile.read(size).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self.send_error(400, "invalid json")
            return
        if not isinstance(data, dict):
            self.send_error(400, "object expected")
            return
        merged = {}
        if FEEDBACK.exists():
            try:
                merged = json.loads(FEEDBACK.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                merged = {}
            if not isinstance(merged, dict):
                merged = {}
        merged.update(data)
        FEEDBACK.write_text(
            json.dumps(merged, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        self._json(200, {"ok": True})

    def log_message(self, fmt, *args):
        pass


def selftest():
    import tempfile
    import threading
    import urllib.request

    # 실제 피드백 파일을 건드리지 않도록 임시 파일로 바꿔 둔다.
    global FEEDBACK
    FEEDBACK = pathlib.Path(tempfile.mkdtemp()) / "feedback.json"

    srv = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = "http://127.0.0.1:%d" % srv.server_address[1]
    def post(payload):
        req = urllib.request.Request(
            base + "/feedback",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            method="POST",
        )
        assert json.load(urllib.request.urlopen(req)) == {"ok": True}

    first = {"drafts": {"d1": {"picked": True, "note": "밝기 조절 필요"}}}
    post(first)
    assert json.load(urllib.request.urlopen(base + "/feedback")) == first

    # 문서 쪽 저장이 초안 피드백을 지우지 않아야 한다.
    post({"docs": {"prd": {"note": "비목표 한 줄 추가"}}})
    both = json.load(urllib.request.urlopen(base + "/feedback"))
    assert both["drafts"] == first["drafts"], both
    assert both["docs"]["prd"]["note"] == "비목표 한 줄 추가", both
    assert json.loads(FEEDBACK.read_text(encoding="utf-8")) == both

    assert urllib.request.urlopen(base + "/PRD.md").status == 200
    srv.shutdown()
    print("selftest ok")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
    else:
        port = int(sys.argv[1]) if len(sys.argv) > 1 else 8123
        socketserver.ThreadingTCPServer.allow_reuse_address = True
        with socketserver.ThreadingTCPServer(("0.0.0.0", port), Handler) as httpd:
            print("serving on http://100.95.86.76:%d/" % port, flush=True)
            httpd.serve_forever()
