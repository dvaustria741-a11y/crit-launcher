# Crit Launcher

Crit Launcher is an open source Minecraft launcher for Android, written in Python and HTML.

The page and Python backend run inside the app (WebView + Chaquopy). The game runs on the engine from
[ZalithLauncher2](https://github.com/ZalithLauncher/ZalithLauncher2), so the Android build is GPLv3
(see [android/NOTICE-GPL.md](android/NOTICE-GPL.md)). Zalith's own UI is not used.

<details>
<summary>notes</summary>
we are not affiliated with microsoft or mojang

we are not affiliated with modrinth or fastclient

we are not affiliated with fabric forge or neoforge

we are not affiliated with mcdonalds or burger king

the themes which aim to replicate other launchers are completely custom built, no original or decompiled code was used for their creation therefore are completly unrelated, if you are fastclient or modrinth kindly create a support ticket in the discord server for any requests.
</details>

## project layout
- `crit_launcher/`: the Python backend, one folder per feature. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
- `crit_launcher/android/`: page-to-Python bridge, mobile CSS, game hand-off.
- `index.html`: the launcher page.
- `android/`: the Kotlin app, the game screen and the Zalith engine glue. See [android/README.md](android/README.md).

tests: `python -m unittest discover -s tests`

## building
Push to `main`. GitHub Actions ("Android build") builds one APK with armeabi-v7a and arm64-v8a and publishes it under Releases.
