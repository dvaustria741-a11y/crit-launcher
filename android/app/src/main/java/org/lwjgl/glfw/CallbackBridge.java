/*
 * Crit Launcher (Android build) - slimmed re-implementation of ZalithLauncher2's CallbackBridge.
 *
 * The engine's native library (libpojavexec.so) looks this class up by name, binds the native methods
 * below with RegisterNatives and calls the @Keep static methods from the game's JVM, so names and
 * signatures must stay exactly as they are. The @CriticalNative annotations must stay too: the native
 * side registers either the critical or the normal variant of each function depending on a runtime probe.
 *
 * Part of the GPLv3 Android build, see android/NOTICE-GPL.md.
 */
package org.lwjgl.glfw;

import androidx.annotation.Keep;

import com.movtery.zalithlauncher.bridge.LoggerBridge;
import com.movtery.zalithlauncher.bridge.NativeLibraryLoader;

import dalvik.annotation.optimization.CriticalNative;

public class CallbackBridge {
    // GLFW mouse buttons / actions
    public static final int BUTTON_LEFT = 0;
    public static final int BUTTON_RIGHT = 1;
    public static final int BUTTON_MIDDLE = 2;
    public static final int ACTION_RELEASE = 0;
    public static final int ACTION_PRESS = 1;

    public static volatile int windowWidth, windowHeight;
    public static volatile boolean isGrabbing;

    /** Listener for state changes coming from the game. */
    public interface GameStateListener {
        void onGrabChanged(boolean grabbing);
        void onFirstFrame();
    }

    private static volatile GameStateListener listener;
    private static volatile boolean firstFrameSeen;

    public static void setListener(GameStateListener l) {
        listener = l;
    }

    // ---- input helpers (UI thread -> game) ----

    public static void sendCursorPos(float x, float y) {
        try { nativeSendCursorPos(x, y); } catch (UnsatisfiedLinkError ignored) { }
    }

    public static void sendMouseButton(int button, boolean down) {
        try { nativeSendMouseButton(button, down ? ACTION_PRESS : ACTION_RELEASE, 0); } catch (UnsatisfiedLinkError ignored) { }
    }

    public static void sendKey(int glfwKey, boolean down) {
        try { nativeSendKey(glfwKey, 0, down ? ACTION_PRESS : ACTION_RELEASE, 0); } catch (UnsatisfiedLinkError ignored) { }
    }

    public static void sendChar(char c) {
        try { nativeSendChar(c); } catch (UnsatisfiedLinkError ignored) { }
    }

    public static void sendScroll(double dx, double dy) {
        try { nativeSendScroll(dx, dy); } catch (UnsatisfiedLinkError ignored) { }
    }

    public static void sendUpdateWindowSize(int w, int h) {
        windowWidth = w;
        windowHeight = h;
        try { nativeSendScreenSize(w, h); } catch (UnsatisfiedLinkError ignored) { }
    }

    // ---- called from the JRE side (looked up in JNI_OnLoad) ----

    @SuppressWarnings("unused")
    @Keep
    public static String accessAndroidClipboard(int type, String copy) {
        // 0 = copy, 1 = paste, 2 = open link (matches the native side); clipboard is handled by ZLNativeInvoker.
        return "";
    }

    @SuppressWarnings("unused")
    @Keep
    public static boolean notifyLauncher(int type, int... action) {
        // SDL integration is not wired up in the Crit build yet.
        return false;
    }

    @SuppressWarnings("unused")
    @Keep
    private static void onDirectInputEnable() {
        LoggerBridge.append("Crit: direct gamepad input requested (not supported yet)");
    }

    @SuppressWarnings("unused")
    @Keep
    private static void onGrabStateChanged(final boolean grabbing) {
        isGrabbing = grabbing;
        GameStateListener l = listener;
        if (l != null) l.onGrabChanged(grabbing);
    }

    @SuppressWarnings("unused")
    @Keep
    private static void onCursorShapeChanged(final int shape) {
        // Touch screens have no visible cursor shape.
    }

    @SuppressWarnings("unused")
    @Keep
    private static void onGraphicOutput() {
        if (firstFrameSeen) return;
        firstFrameSeen = true;
        GameStateListener l = listener;
        if (l != null) l.onFirstFrame();
    }

    // ---- natives (bound by libpojavexec.so with RegisterNatives) ----

    @Keep @CriticalNative public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);

    @Keep @CriticalNative private static native boolean nativeSendChar(char codepoint);
    @Keep @CriticalNative private static native boolean nativeSendCharMods(char codepoint, int mods);
    @Keep @CriticalNative private static native void nativeSendKey(int key, int scancode, int action, int mods);
    @Keep @CriticalNative private static native void nativeSendCursorPos(float x, float y);
    @Keep @CriticalNative private static native void nativeSendMouseButton(int button, int action, int mods);
    @Keep @CriticalNative private static native void nativeResetInputState();
    @Keep @CriticalNative private static native void nativeSendScroll(double xoffset, double yoffset);
    @Keep @CriticalNative private static native void nativeSendScreenSize(int width, int height);

    static {
        NativeLibraryLoader.loadPojavLib();
    }
}
