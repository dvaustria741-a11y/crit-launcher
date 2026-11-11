"""HTTP helpers shared by every feature (JSON/text/bytes fetches, downloads, authenticated calls)."""

import http.client
import json
import os
import threading
import time
import urllib.parse
import urllib.error
import urllib.request
from pathlib import Path

from .fsutil import file_sha1


USER_AGENT = "CritLauncher/1.0 (+https://critlauncher.xyz)"


def http_get_json(url: str, timeout: int = 20, retries: int = 2):
    last = None
    for attempt in range(retries + 1):
        try:
            req = urllib.request.Request(
                url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            if exc.code in (400, 401, 403, 404):
                raise
            last = exc
        except Exception as exc:
            last = exc
        time.sleep(0.8 * (attempt + 1))
    raise last


def http_post_json(url: str, payload, timeout: int = 30):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST", headers={
        "User-Agent": USER_AGENT, "Accept": "application/json", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def http_get_text(url: str, timeout: int = 30) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read().decode("utf-8", "replace")


def _json_or_text(raw: bytes):
    try:
        return json.loads(raw.decode("utf-8"))
    except Exception:
        return {"raw": raw.decode("utf-8", "replace")[:300]}


def http_get_auth(url: str, token: str, timeout: int = 25):
    req = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {token}", "User-Agent": USER_AGENT, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, _json_or_text(resp.read())
    except urllib.error.HTTPError as exc:
        return exc.code, _json_or_text(exc.read())


def http_download(url: str, dest: Path, expected_size=None, expected_sha1=None,
                  progress_cb=None, chunk_size: int = 65536, user_agent=None):
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name(dest.name + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": user_agent or USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as resp, open(tmp, "wb") as f:
        while True:
            chunk = resp.read(chunk_size)
            if not chunk:
                break
            f.write(chunk)
            if progress_cb:
                progress_cb(len(chunk))
    if expected_sha1:
        if file_sha1(tmp) != expected_sha1:
            tmp.unlink(missing_ok=True)
            raise IOError(f"Checksum mismatch for {dest.name}")
    os.replace(tmp, dest)


_conns = threading.local()


def _pooled_conn(scheme: str, netloc: str):
    pool = getattr(_conns, "pool", None)
    if pool is None:
        pool = _conns.pool = {}
    conn = pool.get((scheme, netloc))
    if conn is None:
        cls = http.client.HTTPSConnection if scheme == "https" else http.client.HTTPConnection
        conn = pool[(scheme, netloc)] = cls(netloc, timeout=30)
    return conn


def _drop_conn(scheme: str, netloc: str):
    conn = getattr(_conns, "pool", {}).pop((scheme, netloc), None)
    if conn is not None:
        try:
            conn.close()
        except Exception:
            pass


def http_download_fast(url: str, dest: Path, expected_size=None, expected_sha1=None,
                       progress_cb=None, chunk_size: int = 262144):
    """Like http_download, but each worker thread keeps its connection open, so thousands of small files
    (game assets) do not each pay for a new TLS handshake. Redirects fall back to http_download."""
    parts = urllib.parse.urlsplit(url)
    if parts.scheme not in ("http", "https"):
        return http_download(url, dest, expected_size, expected_sha1, progress_cb)
    path = parts.path + ("?" + parts.query if parts.query else "")
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name(dest.name + ".part")
    last = None
    for _ in range(2):
        conn = _pooled_conn(parts.scheme, parts.netloc)
        try:
            conn.request("GET", path, headers={"User-Agent": USER_AGENT, "Accept-Encoding": "identity"})
            resp = conn.getresponse()
            if 300 <= resp.status < 400:
                resp.read()
                return http_download(url, dest, expected_size, expected_sha1, progress_cb)
            if resp.status != 200:
                resp.read()
                raise IOError(f"HTTP {resp.status} for {url}")
            with open(tmp, "wb") as f:
                while True:
                    chunk = resp.read(chunk_size)
                    if not chunk:
                        break
                    f.write(chunk)
                    if progress_cb:
                        progress_cb(len(chunk))
            break
        except (http.client.HTTPException, ConnectionError, TimeoutError, OSError) as exc:
            # a kept-alive connection may have been closed by the server: reconnect once
            last = exc
            _drop_conn(parts.scheme, parts.netloc)
    else:
        raise last
    if expected_sha1 and file_sha1(tmp) != expected_sha1:
        tmp.unlink(missing_ok=True)
        raise IOError(f"Checksum mismatch for {dest.name}")
    os.replace(tmp, dest)


def http_get_bytes(url: str, limit=None, timeout: int = 60) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read(limit) if limit else resp.read()


def http_send(method: str, url: str, token=None, data=None, content_type=None, timeout: int = 30, headers=None):
    hdrs = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    if token:
        hdrs["Authorization"] = f"Bearer {token}"
    if content_type:
        hdrs["Content-Type"] = content_type
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, _json_or_text(resp.read())
    except urllib.error.HTTPError as exc:
        return exc.code, _json_or_text(exc.read())
