package com.poolaim.android.capture

import java.util.concurrent.atomic.AtomicReference

/** Single-slot mailbox: only the newest captured frame is retained. */
class LatestFrameMailbox {
    private val latest = AtomicReference<CapturedRgbaFrame?>(null)

    fun publish(frame: CapturedRgbaFrame) {
        latest.set(frame)
    }

    fun peek(): CapturedRgbaFrame? = latest.get()

    fun take(): CapturedRgbaFrame? = latest.getAndSet(null)

    fun clear() {
        latest.set(null)
    }
}
