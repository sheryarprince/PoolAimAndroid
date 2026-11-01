package com.poolaim.android

/**
 * Honest status gate for the AimPool experiment.
 *
 * The original APK uses a protected-code runtime to initialize and register
 * the native API. Loading libapool directly from this app is not yet known to
 * reproduce that initialization, so this probe deliberately does not load it.
 */
object AimPoolNativeProbe {
    data class Status(val loaded: Boolean, val detail: String)

    fun check(): Status = Status(
        false,
        "Native engine not initialized: original protected model/scene setup is not yet recovered. Open docs/AIMPOOL_NATIVE_INIT_FINDINGS.md."
    )
}
