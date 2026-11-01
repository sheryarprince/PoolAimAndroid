package com.team.aimpool.ai.team.ntpool;

/**
 * JNI facade reconstructed from the authorized AimPool APK's DEX signatures.
 * Native methods are static and registered by libapool during JNI_OnLoad.
 */
public final class AimPool {
    static { System.loadLibrary("apool"); }
    private AimPool() {}

    public static native Object findAimPoollyInfo(String sceneHandle);
    public static native void freeExpModel(String handle);
    public static native void freeExpScene(String handle);
    public static native Object getLengthLine(String sceneHandle);
    public static native int putAPoolScene(Object scene);
    public static native void removeBlackFrame(String handle);
    public static native int start(Object scene);

    public static APoolBlackFrame findBlackFrame(String sceneHandle) {
        return (APoolBlackFrame) findAimPoollyInfo(sceneHandle);
    }

    public static APoolBallsInfo lengthenLine(String sceneHandle) {
        return (APoolBallsInfo) getLengthLine(sceneHandle);
    }

    public static int putScene(APoolScene scene) {
        return putAPoolScene(scene);
    }
}
