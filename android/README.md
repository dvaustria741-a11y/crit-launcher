# Crit Launcher for Android

A Kotlin WebView shell (`app/`) that runs the existing Python backend in-process with
[Chaquopy](https://chaquo.com/chaquopy/) and shows `index.html`. The pywebview bridge is replaced by
`crit_launcher/android/` (`entry.py` + `bridge.js`).

* ABIs: `armeabi-v7a` and `arm64-v8a` (one APK holds both).
* Build: pushes to `main` build a debug APK in GitHub Actions (artifact `crit-launcher-android-debug`).
  Locally: `cd android && gradle assembleDebug` (JDK 17, Android SDK, Python 3.11 on PATH).

## Launch engine
Minecraft launching reuses the ZalithLauncher2 engine (GPLv3, see `NOTICE-GPL.md`). It needs a newer toolchain
than Chaquopy supports, so it is built by `.github/workflows/android-engine.yml` (called from `android.yml`) at the
commit in `engine/ZALITH_PIN`, and `engine/extract-engine.sh` unpacks its native `.so` files, the JRE packs and
the LWJGL components into `app/src/main/jniLibs` and `assets/engine` (both git-ignored) before the app is built.

How a launch runs:
1. The Python backend builds the java command as on desktop, writes it to `android_launch.json`
   (`crit_launcher/android/game.py`) and asks the shell to open `GameActivity`.
2. `GameActivity` runs in its own process (`:game`). Its SurfaceView is handed to the engine
   (`ZLBridge.setupBridgeWindow`), then `engine/EngineLauncher.kt` follows Zalith's sequence: library path,
   environment, dlopen the JRE and the GL4ES renderer, `VMLauncher.launchJVM`.
3. Desktop LWJGL jars on the classpath are swapped for the engine's LWJGL 3.3.3; the JRE (8/17/21/25) is the
   one that matches the version's Java requirement and is unpacked on first use.
4. When the JVM exits the game process tells the launcher (broadcast) and ends itself.

The Java classes the native libraries bind to are in `app/src/main/java`: `ZLBridge`, `LoggerBridge`,
`NativeLibraryLoader`, `CriticalNativeTest`, `VMLauncher`, `GraphicOutputListener` are Zalith's files unchanged;
`CallbackBridge` and `ZLNativeInvoker` are slimmed versions with the same names and signatures.

## Status / TODO
* [x] UI + backend boot, JS <-> Python bridge, open links in the browser
* [x] Mobile layout (bottom tab bar, touch targets, portrait + landscape)
* [x] Game screen + engine hand-off (written, **not yet run on a device**): touch look/click, on-screen WASD,
      jump, sneak, use, inventory, Esc/chat/F3/F5, hardware keyboard
* [ ] Verify launching on a real phone (Adreno 619); expect bugs in env/dlopen order
* [ ] LWJGL 3.4.1 for the newest Minecraft versions, SDL input, gamepads, renderer choice (only GL4ES now)
* [ ] Microsoft sign-in (needs a Kotlin WebView/Custom Tab instead of `webview.create_window`)
* [ ] File picker for importers (Storage Access Framework)
