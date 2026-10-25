#!/usr/bin/env python3
"""
A small fake TMDB and OMDb for testing HomeReel's online movie info without real keys. All data is made up.

    python3 tools/mock_tmdb.py [port]          # default 8765, listens on 127.0.0.1 only

In a debug build of HomeReel Server on the Android emulator, open the admin page, then Settings, and fill in:
    TMDB API key: test-tmdb-key                    OMDb API key: test-omdb-key
    Test hook: TMDB base URL:  http://10.0.2.2:8765/3
    Test hook: image base URL: http://10.0.2.2:8765/t/p
    Test hook: OMDb base URL:  http://10.0.2.2:8765/omdb
(10.0.2.2 is the computer, seen from the emulator.) Release builds ignore these addresses.

Files whose names match a film or show below get matched: "Dangal (2016).mp4", "The Lunchbox 2013.mp4",
"Panchayat S01E02.mkv". To test failures, create these files in the state folder printed at start:
    drop_next   containing a number: hang up on that many requests, like a network blip
    omdb_down   OMDb answers 503
    img_down    pictures answer 503
Every request is printed, so you can check the one-per-second pace.
"""
import base64, json, os, re, sys, tempfile, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

STATE = os.environ.get("MOCK_TMDB_STATE") or os.path.join(tempfile.gettempdir(), "homereel-mock-tmdb")
os.makedirs(STATE, exist_ok=True)
TMDB_KEY, OMDB_KEY = "test-tmdb-key", "test-omdb-key"
# A 32x48 dark blue JPEG, served for every poster, backdrop and still.
PICTURE = base64.b64decode("/9j/4AAQSkZJRgABAQAASABIAAD/4QBMRXhpZgAATU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAA6ABAAMAAAABAAEAAKACAAQAAAABAAAAIKADAAQAAAABAAAAMAAAAAD/7QA4UGhvdG9zaG9wIDMuMAA4QklNBAQAAAAAAAA4QklNBCUAAAAAABDUHYzZjwCyBOmACZjs+EJ+/8AAEQgAMAAgAwEiAAIRAQMRAf/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/EAB8BAAMBAQEBAQEBAQEAAAAAAAABAgMEBQYHCAkKC//EALURAAIBAgQEAwQHBQQEAAECdwABAgMRBAUhMQYSQVEHYXETIjKBCBRCkaGxwQkjM1LwFWJy0QoWJDThJfEXGBkaJicoKSo1Njc4OTpDREVGR0hJSlNUVVZXWFlaY2RlZmdoaWpzdHV2d3h5eoKDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uLj5OXm5+jp6vLz9PX29/j5+v/bAEMABAQEBAQEBgQEBgkGBgYJDAkJCQkMDwwMDAwMDxIPDw8PDw8SEhISEhISEhUVFRUVFRkZGRkZHBwcHBwcHBwcHP/bAEMBBAUFBwcHDAcHDB0UEBQdHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHf/dAAQAAv/aAAwDAQACEQMRAD8A+d6KKK+4PDCiiigD/9D53ooor7g8MKKKKAP/0fneiiivuDwwooooA//Z")


def log(text):
    print(f"{time.time():.3f} {text}", file=sys.stderr, flush=True)


FILMS = {
    101: dict(title="Dangal", original_title="Dangal", release_date="2016-12-21", imdb_id="tt0000101",
              overview="A former wrestler trains his daughters to compete, against everyone's expectations.",
              tagline="Test tagline: the fight begins at home.", genres=["Drama", "Sport", "Biography"],
              cast=["Actor One", "Actor Two", "Actor Three", "Actor Four", "Actor Five", "Actor Six", "Actor Seven"],
              director="Director One", cert_in="U", vote=8.0, collection=None),
    102: dict(title="The Lunchbox", original_title="Dabba", release_date="2013-09-20", imdb_id="tt0000102",
              overview="A lunchbox delivered to the wrong desk starts an exchange of letters.",
              tagline="", genres=["Drama", "Romance"], cast=["Actor Eight", "Actor Nine"],
              director="Director Two", cert_in="", cert_us="PG", vote=7.7, collection=None),
    201: dict(title="Bullet Train", original_title="Bullet Train", release_date="2016-05-01", imdb_id="tt0000201",
              overview="A made-up action film.", tagline="", genres=["Action"], cast=[], director="Director Three",
              cert_in="UA 13+", vote=6.0, collection="Fake Collection"),
    202: dict(title="Night Shift", original_title="Night Shift", release_date="2016-02-02", imdb_id="tt0000202",
              overview="A made-up thriller.", tagline="", genres=["Thriller"], cast=[], director="Director Four",
              cert_in="A", vote=5.5, collection=None),
}

SHOWS = {
    301: dict(name="Panchayat", original_name="Panchayat", first_air_date="2020-04-03", last_air_date="2024-05-28",
              in_production=True, imdb_id="tt0000301", overview="An engineering graduate takes a job as secretary of a village council.",
              genres=["Comedy", "Drama"], cast=["Actor A", "Actor B", "Actor C"], creators=["Creator One"], cert_in="U/A 13+", vote=8.9,
              seasons={1: ["Gram Panchayat", "Kaam Ki Baat", "Pehla Mauka"], 2: ["Naach", "Daak"]}),
    302: dict(name="Panchayat Diaries", original_name="Panchayat Diaries", first_air_date="2021-01-01", last_air_date="2021-02-01",
              in_production=False, imdb_id="tt0000302", overview="A made-up show.", genres=["Documentary"], cast=[], creators=[],
              cert_in="", vote=5.0, seasons={1: ["One"]}),
}

def words(s): return set(re.sub(r"[^a-z0-9 ]", " ", s.lower()).split()) - {"the", "a", "an"}

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(data)))
        self.end_headers(); self.wfile.write(data)

    def flag(self, name): return os.path.exists(os.path.join(STATE, name))

    def drop(self):
        """Simulate a network blip: hang up without answering."""
        self.close_connection = True
        try: self.connection.shutdown(2)
        except OSError: pass

    def do_GET(self):
        u = urlparse(self.path); q = parse_qs(u.query)
        log(f"{u.path} {q.get('query', [''])[0]}")
        counter = os.path.join(STATE, "drop_next")
        if os.path.exists(counter) and not u.path.startswith("/t/p/"):
            n = int(open(counter).read().strip() or 0)
            if n > 0:
                open(counter, "w").write(str(n - 1))
                log(f"DROPPED {u.path}")
                return self.drop()
        if u.path.startswith("/t/p/") and self.flag("img_down"):
            return self.send(503, {})
        if u.path.startswith("/omdb") and self.flag("omdb_down"):
            return self.send(503, {"Response": "False", "Error": "Service unavailable"})
        if u.path.startswith("/t/p/"):
            return self.send(200, PICTURE, "image/jpeg")
        if u.path.startswith("/omdb"):
            if q.get("apikey", [""])[0] != OMDB_KEY:
                return self.send(401, {"Response": "False", "Error": "Invalid API key!"})
            imdb = q.get("i", [""])[0]
            rating = {"tt0000101": "8.3", "tt0000102": "7.8", "tt0000301": "9.0"}.get(imdb, "N/A")
            return self.send(200, {"Response": "True", "imdbRating": rating})
        key = q.get("api_key", [""])[0] or self.headers.get("Authorization", "").removeprefix("Bearer ")
        if key != TMDB_KEY:
            return self.send(401, {"status_message": "Invalid API key"})
        if u.path == "/3/configuration":
            return self.send(200, {"images": {}})
        if u.path == "/3/search/movie":
            query = words(q.get("query", [""])[0]); year = q.get("year", [None])[0]
            hits = []
            for fid, f in FILMS.items():
                if not (query & words(f["title"]) or query & words(f["original_title"])): continue
                if year and f["release_date"][:4] != year: continue
                hits.append(dict(id=fid, title=f["title"], original_title=f["original_title"], release_date=f["release_date"],
                                 overview=f["overview"], poster_path=f"/p{fid}.jpg" if fid < 200 else None))
            return self.send(200, {"page": 1, "results": hits})
        if u.path == "/3/search/tv":
            query = words(q.get("query", [""])[0]); year = q.get("first_air_date_year", [None])[0]
            hits = [dict(id=sid, name=sh["name"], original_name=sh["original_name"], first_air_date=sh["first_air_date"],
                         overview=sh["overview"], poster_path=f"/p{sid}.jpg") for sid, sh in SHOWS.items()
                    if query & words(sh["name"]) and (not year or sh["first_air_date"][:4] == year)]
            return self.send(200, {"page": 1, "results": hits})
        m = re.fullmatch(r"/3/tv/(\d+)/season/(\d+)", u.path)
        if m and int(m.group(1)) in SHOWS:
            sid, n = int(m.group(1)), int(m.group(2)); names = SHOWS[sid]["seasons"].get(n, [])
            return self.send(200, {"episodes": [dict(episode_number=i + 1, name=name, overview=f"Made-up episode {i + 1} of season {n}.",
                                   air_date=f"202{n - 1}-04-0{i + 1}", still_path=f"/s{sid}_{i + 1}.jpg" if n == 1 else None)
                                   for i, name in enumerate(names)]})
        m = re.fullmatch(r"/3/tv/(\d+)", u.path)
        if m and int(m.group(1)) in SHOWS:
            sid = int(m.group(1)); sh = SHOWS[sid]
            return self.send(200, dict(
                id=sid, name=sh["name"], first_air_date=sh["first_air_date"], last_air_date=sh["last_air_date"],
                in_production=sh["in_production"], overview=sh["overview"], genres=[{"name": g} for g in sh["genres"]],
                vote_average=sh["vote"], vote_count=900, poster_path=f"/p{sid}.jpg", backdrop_path=f"/b{sid}.jpg",
                created_by=[{"name": c} for c in sh["creators"]],
                aggregate_credits={"cast": [{"name": n, "order": i} for i, n in enumerate(sh["cast"])]},
                content_ratings={"results": [{"iso_3166_1": "IN", "rating": sh["cert_in"]}]},
                external_ids={"imdb_id": sh["imdb_id"]}))
        m = re.fullmatch(r"/3/movie/(\d+)", u.path)
        if m and int(m.group(1)) in FILMS:
            fid = int(m.group(1)); f = FILMS[fid]
            rel = []
            if f.get("cert_in") is not None: rel.append({"iso_3166_1": "IN", "release_dates": [{"certification": f["cert_in"], "type": 3}]})
            if f.get("cert_us"): rel.append({"iso_3166_1": "US", "release_dates": [{"certification": f["cert_us"], "type": 3}]})
            return self.send(200, dict(
                id=fid, title=f["title"], release_date=f["release_date"], overview=f["overview"], tagline=f["tagline"],
                genres=[{"id": i, "name": g} for i, g in enumerate(f["genres"])], vote_average=f["vote"], vote_count=5000,
                poster_path=f"/p{fid}.jpg" if fid < 200 else None, backdrop_path=f"/b{fid}.jpg" if fid < 200 else None,
                belongs_to_collection={"name": f["collection"]} if f["collection"] else None, imdb_id=f["imdb_id"],
                credits={"cast": [{"name": n, "order": i} for i, n in enumerate(f["cast"])], "crew": [{"name": f["director"], "job": "Director"}]},
                release_dates={"results": rel}))
        return self.send(404, {"status_message": "not found"})

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    log(f"Mock TMDB on http://127.0.0.1:{port}, state folder {STATE}")
    ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
