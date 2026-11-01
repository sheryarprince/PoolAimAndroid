package com.team.aimpool.ai.team.ntpool;

/**
 * Minimal JNI registration shim recovered from the authorized AimPool APK.
 * This intentionally exposes the original signatures without invoking them:
 * their object contracts are app-specific and have not yet been reconstructed.
 */
public final class AimPool {
    static { System.loadLibrary("apool"); }
    private AimPool() {}

    public static native Object findAimPoollyInfo(String input);
    public static native void freeExpModel(String handle);
    public static native void freeExpScene(String handle);
    public static native Object getLengthLine(String input);
    public static native int putAPoolScene(Object scene);
    public static native void removeBlackFrame(String handle);
    public static native int start(Object scene);
}
