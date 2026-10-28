#!/usr/bin/env python3
"""
A small fake OpenSubtitles for testing Find subtitles without a real key. All data is made up.

    python3 tools/mock_opensubtitles.py [port] [downloads]     # default 8768 and 3; listens on 127.0.0.1 only

In a debug build of HomeReel Server on the Android emulator, open Settings → Movie info on the admin page and fill in:
    OpenSubtitles API key: test-os-key        Subtitle languages: en, hi
    Test hook: OpenSubtitles base URL: http://10.0.2.2:8768/api/v1
(10.0.2.2 is the computer, seen from the emulator.) Release builds ignore the address.

Every search finds an English and a Hindi subtitle. After [downloads] downloads it answers like OpenSubtitles does
when the daily limit is reached. Every request is printed, with its parameters, so you can check what was sent.
"""
import json, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8768
left = int(sys.argv[2]) if len(sys.argv) > 2 else 3
KEY = "test-os-key"
SUBS = {
    101: "1\n00:00:01,000 --> 00:00:04,000\nHello from OpenSubtitles\n",
    202: "1\n00:00:01,000 --> 00:00:04,000\nनमस्ते, OpenSubtitles से\n",
}


class Handler(BaseHTTPRequestHandler):
    def reply(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass

    def keyed(self):
        if self.headers.get("Api-Key") != KEY:
            self.reply(401, {"message": "You cannot consume this service"})
            return False
        if not self.headers.get("User-Agent", "").startswith("HomeReel v"):
            self.reply(403, {"message": "Set a User-Agent naming your app"})
            return False
        return True

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        print(f"{time.time():.3f} GET {u.path} {q} agent={self.headers.get('User-Agent')}", flush=True)
        if u.path.startswith("/dl/"):
            sid = int(u.path.removeprefix("/dl/").removesuffix(".srt"))
            return self.reply(200, SUBS[sid].encode(), "application/x-subrip")
        if not self.keyed():
            return
        if u.path == "/api/v1/infos/formats":
            return self.reply(200, {"data": {"output_formats": ["srt", "vtt"]}})
        if u.path == "/api/v1/subtitles":
            langs = q.get("languages", "").split(",")
            data = []
            if "en" in langs:
                data.append({"id": "1", "type": "subtitle", "attributes": {"language": "en", "download_count": 900,
                             "moviehash_match": "moviehash" in q, "files": [{"file_id": 101, "file_name": "en.srt"}]}})
            if "hi" in langs:
                data.append({"id": "2", "type": "subtitle", "attributes": {"language": "hi", "download_count": 40,
                             "files": [{"file_id": 202, "file_name": "hi.srt"}]}})
            return self.reply(200, {"total_count": len(data), "data": data})
        self.reply(404, {"message": "Not found"})

    def do_POST(self):
        global left
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        print(f"{time.time():.3f} POST {self.path} {body}", flush=True)
        if not self.keyed():
            return
        if self.path == "/api/v1/download":
            if left <= 0:
                return self.reply(406, {"message": "You have downloaded your allowed 0 subtitles for 24h.",
                                        "remaining": 0, "reset_time_utc": "2099-01-01T00:00:00.000Z"})
            left -= 1
            host = self.headers.get("Host", f"127.0.0.1:{PORT}")
            return self.reply(200, {"link": f"http://{host}/dl/{body['file_id']}.srt", "file_name": "x.srt",
                                    "requests": 1, "remaining": left, "message": "ok"})
        self.reply(404, {"message": "Not found"})


print(f"Mock OpenSubtitles on http://127.0.0.1:{PORT}, {left} downloads", flush=True)
ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
