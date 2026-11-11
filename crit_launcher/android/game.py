"""Hands a launch over to the Android game screen (Zalith engine) instead of spawning a desktop java."""

import json
import subprocess
import threading

from ..config.paths import DATA_DIR

_host = None
_proc = None
_lock = threading.Lock()


def set_host(host):
    global _host
    _host = host


class GameProc:
    """Looks like a subprocess.Popen to the launch API; finished by game_exited()."""

    def __init__(self):
        self._done = threading.Event()
        self.returncode = None

    def wait(self, timeout=None):
        if not self._done.wait(timeout):
            raise subprocess.TimeoutExpired("android-game", timeout)
        return self.returncode

    def poll(self):
        return self.returncode if self._done.is_set() else None

    def terminate(self):
        if _host is not None:
            _host.stopGame()

    kill = terminate

    def _finish(self, code):
        self.returncode = code
        self._done.set()


def start(instance_name, command, game_dir, log_path, java_major):
    """command[0] is the (ignored) java executable; the engine supplies its own runtime."""
    global _proc
    if _host is None:
        raise RuntimeError("The game screen is not available.")
    spec = {
        "major": int(java_major or 17),
        "args": [str(a) for a in command[1:]],
        "game_dir": str(game_dir),
        "log": str(log_path),
        "instance": instance_name,
    }
    spec_path = DATA_DIR / "android_launch.json"
    spec_path.parent.mkdir(parents=True, exist_ok=True)
    spec_path.write_text(json.dumps(spec), encoding="utf-8")
    proc = GameProc()
    with _lock:
        _proc = proc
    _host.startGame(str(spec_path), str(log_path))
    return proc


def game_exited(code):
    global _proc
    with _lock:
        proc, _proc = _proc, None
    if proc is not None:
        proc._finish(int(code))
