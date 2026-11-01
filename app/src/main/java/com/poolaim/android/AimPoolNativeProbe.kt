package com.poolaim.android

import android.util.Log
import com.team.aimpool.ai.team.ntpool.AimPool

/**
 * Loads the AimPool native engine and reports whether JNI registration succeeds.
 * No native aiming methods are called until their original object contracts are known.
 */
object AimPoolNativeProbe {
    data class Status(val loaded: Boolean, val detail: String)

    @Volatile private var cached: Status? = null

    fun check(): Status {
        cached?.let { return it }
        val result = try {
            // Referencing the shim initializes its class and runs System.loadLibrary.
            Class.forName("com.team.aimpool.ai.team.ntpool.AimPool", true, AimPool::class.java.classLoader)
            Status(true, "libapool loaded; JNI shim class resolved")
        } catch (t: Throwable) {
            Log.e("AimPoolNative", "Native engine load failed", t)
            Status(false, "${t.javaClass.simpleName}: ${t.message ?: "no details"}")
        }
        cached = result
        return result
    }
}
