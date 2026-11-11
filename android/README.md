# Crit Launcher for Android

A Kotlin WebView shell (`app/`) that runs the existing Python backend in-process with
[Chaquopy](https://chaquo.com/chaquopy/) and shows `index.linux.html`. The pywebview bridge is replaced by
`crit_launcher/android/` (`entry.py` + `bridge.js`).

* ABIs: `armeabi-v7a` and `arm64-v8a` (one APK holds both).
* Build: pushes to `main` build a debug APK in GitHub Actions (artifact `crit-launcher-android-debug`).
  Locally: `cd android && gradle assembleDebug` (JDK 17, Android SDK, Python 3.12 on PATH).

## Launch engine (in progress)
Minecraft launching reuses the ZalithLauncher2 engine (GPLv3, see `NOTICE-GPL.md`). It needs a newer toolchain
than Chaquopy supports, so it is built separately: `.github/workflows/android-engine.yml` builds Zalith at the
commit in `engine/ZALITH_PIN` (arm + arm64), and `engine/extract-engine.sh` unpacks its `.so` files and
runtimes into this app.

Next steps: copy the Java classes the native libs bind to (`ZLBridge`, `CallbackBridge`, `LoggerBridge`,
`SdlBridge`, ...) verbatim, add a `GameActivity` with a Surface that starts the JVM, and have the Python
side hand it the launch command it already builds.

## Status / TODO
* [x] UI + backend boot, JS <-> Python bridge, open links in the browser
* [ ] Java runtime for Android (Minecraft launch needs a bundled ARM JRE and a renderer, e.g. as in PojavLauncher)
* [ ] Microsoft sign-in (needs a Kotlin WebView/Custom Tab instead of `webview.create_window`)
* [ ] File picker for importers (Storage Access Framework), touch-friendly layout, Windows-only features hidden
