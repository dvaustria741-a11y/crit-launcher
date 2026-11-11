"""Android entry point.

The Kotlin shell calls start(), then loads the returned page in a WebView. Page calls arrive in
dispatch() (replacing pywebview's js_api); Python -> page events go out through AndroidWindow.
"""

import json
import os
import threading


class AndroidWindow:
    """Minimal stand-in for a pywebview window."""

    title = "Crit Launcher"

    def __init__(self, host):
        self._host = host

    def evaluate_js(self, code):
        self._host.evalJs(code)

    def destroy(self):
        self._host.finishApp()

    def create_file_dialog(self, *args, **kwargs):
        # TODO: Storage Access Framework picker for importers.
        return None


_api = None
_methods = []


def _public_methods(api):
    out = []
    for name in dir(api):
        if name.startswith("_"):
            continue
        try:
            if callable(getattr(api, name)):
                out.append(name)
        except Exception:
            pass
    return out


def start(host, data_dir, resource_dir):
    """Initialise the backend. Returns the absolute path of the page to load."""
    global _api, _methods
    os.environ["CRIT_LAUNCHER_DATA"] = data_dir
    os.environ["CRIT_RESOURCE_DIR"] = resource_dir

    from ..bootstrap import init as init_environment
    from ..ui.page import prepare_page, start_asset_download
    from ..app.facade import API

    init_environment()
    start_asset_download()
    api = API()
    api.set_window(AndroidWindow(host))
    api.open_url = lambda url: _open_url(host, url)
    _api = api
    _methods = _public_methods(api)
    try:
        api._presence_sync()
    except Exception:
        pass

    page = prepare_page()
    return _inject_bridge(str(page))


def _open_url(host, url):
    if not isinstance(url, str) or not url.startswith(("https://", "http://", "mailto:")):
        return {"success": False}
    host.openUrl(url)
    return {"success": True}


def _inject_bridge(page_path):
    """Write the page with the pywebview shim inlined right after <head>."""
    shim_path = os.path.join(os.path.dirname(__file__), "bridge.js")
    with open(shim_path, "r", encoding="utf-8") as fh:
        shim = fh.read().replace("__CRIT_METHODS__", json.dumps(_methods))
    with open(page_path, "r", encoding="utf-8", errors="replace") as fh:
        html = fh.read()
    tag = "<script>" + shim + "</script>"
    if "<head>" in html:
        html = html.replace("<head>", "<head>" + tag, 1)
    else:
        html = tag + html
    out = page_path + ".android.html"
    with open(out, "w", encoding="utf-8", newline="") as fh:
        fh.write(html)
    return out


def dispatch(method, args_json):
    """Run one page -> Python call; always returns a JSON string."""
    try:
        if _api is None or method.startswith("_") or method not in _methods:
            return json.dumps({"success": False, "error": "Unknown method: " + str(method)})
        args = json.loads(args_json) if args_json else []
        result = getattr(_api, method)(*args)
        return json.dumps(result, default=str)
    except Exception as exc:  # the page expects a result, never an exception
        try:
            from ..core.logs import log_exception
            log_exception("android dispatch " + str(method))
        except Exception:
            pass
        return json.dumps({"success": False, "error": str(exc)})


def shutdown():
    if _api is not None:
        try:
            _api._presence_sync_off()
            _api.stop_all_servers()
        except Exception:
            pass
