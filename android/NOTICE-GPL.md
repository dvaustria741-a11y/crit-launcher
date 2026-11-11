# Licensing of the Android build

Crit Launcher itself is MIT. The Android app embeds the launch engine of
[ZalithLauncher2](https://github.com/ZalithLauncher/ZalithLauncher2) (JRE runtimes, native bridges, LWJGL
patches, renderers), which is licensed under the **GNU GPL v3**. A distributed Android build that includes
it is therefore a combined work and must be offered under GPLv3 too, with source available.

* Pinned upstream commit: see `engine/ZALITH_PIN`.
* Upstream code is fetched at build time and is not copied into this repository, except for these small
  Java bridge classes, which keep their original GPL headers: `ZLBridge`, `LoggerBridge`, `NativeLibraryLoader`,
  `CriticalNativeTest`, `VMLauncher`, `GraphicOutputListener` (under `android/app/src/main/java`).
  `CallbackBridge` and `ZLNativeInvoker` there are slimmed re-implementations with the same names/signatures.
* The MIT parts of Crit stay MIT when used on their own (desktop builds are unaffected).
