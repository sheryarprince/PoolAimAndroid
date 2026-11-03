package com.poolaim.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.DashPathEffect
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import com.poolaim.android.capture.CapturedRgbaFrame
import com.poolaim.android.capture.LatestFrameMailbox
import com.poolaim.android.engine.CalibratedTableTransform
import com.poolaim.android.engine.FloatPoint
import com.poolaim.android.vision.BallIdentity
import com.poolaim.android.vision.BallKind
import com.poolaim.android.vision.PoolAimProcessor
import com.poolaim.android.vision.ProcessedPoolFrame
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

class OverlayCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "poolaim_capture"
        private const val CAPTURE_SAMPLE_INTERVAL_MS = 50L
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var visionThread: HandlerThread? = null
    private var visionHandler: Handler? = null
    private lateinit var windowManager: WindowManager
    private var statusBadge: StatusBadgeView? = null
    private var panel: LinearLayout? = null
    private var statusText: TextView? = null
    private val latestFrames = LatestFrameMailbox()
    private val aimProcessor = PoolAimProcessor()
    private val processingScheduled = AtomicBoolean(false)
    @Volatile private var processedScene: ProcessedPoolFrame? = null
    private var capturedFrameCount = 0L
    private var processedFrameCount = 0L
    private var captureErrorCount = 0L
    private var lastSampleAt = 0L

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        makeNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification())
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultData != null && projection == null) startCapture(resultCode, resultData)
        addStatusBadge()
        addPanel()
        refreshStatus()
        return START_NOT_STICKY
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val metrics = resources.displayMetrics
        captureThread = HandlerThread("PoolAimFrameCapture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)
        visionThread = HandlerThread("PoolAimVision").apply { start() }
        visionHandler = Handler(visionThread!!.looper)

        reader = ImageReader.newInstance(
            metrics.widthPixels.coerceAtLeast(1),
            metrics.heightPixels.coerceAtLeast(1),
            PixelFormat.RGBA_8888,
            2
        )
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                reader?.close()
                reader = null
                virtualDisplay?.release()
                virtualDisplay = null
                stopSelf()
            }
        }, captureHandler)

        reader?.setOnImageAvailableListener({ source ->
            val image = try { source.acquireLatestImage() } catch (_: Exception) { null }
            if (image != null) {
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSampleAt >= CAPTURE_SAMPLE_INTERVAL_MS) {
                        lastSampleAt = now
                        latestFrames.publish(CapturedRgbaFrame.copyFrom(image))
                        capturedFrameCount++
                        scheduleVisionProcessing()
                        refreshStatus()
                    }
                } catch (_: Exception) {
                    captureErrorCount++
                    refreshStatus()
                } finally {
                    image.close()
                }
            }
        }, captureHandler)

        virtualDisplay = projection?.createVirtualDisplay(
            "PoolAimCapture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            captureHandler
        )
    }

    /** One worker at a time; intermediate frames are replaced by the latest frame. */
    private fun scheduleVisionProcessing() {
        if (!processingScheduled.compareAndSet(false, true)) return
        val handler = visionHandler
        if (handler == null) {
            processingScheduled.set(false)
            return
        }
        handler.post {
            try {
                val frame = latestFrames.take()
                if (frame != null) {
                    processedScene = aimProcessor.process(frame)
                    processedFrameCount++
                    statusBadge?.post {
                        statusBadge?.scene = processedScene
                        statusBadge?.invalidate()
                    }
                    refreshStatus()
                }
            } catch (_: Exception) {
                captureErrorCount++
                refreshStatus()
            } finally {
                processingScheduled.set(false)
                if (latestFrames.peek() != null) scheduleVisionProcessing()
            }
        }
    }

    private fun addStatusBadge() {
        if (statusBadge != null) return
        val badge = StatusBadgeView()
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(badge, params)
        statusBadge = badge
    }

    private fun addPanel() {
        if (panel != null) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.LTGRAY)
            }
            elevation = dp(8).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = "POOL AIM"
                textSize = 14f
                setTextColor(Color.rgb(20, 80, 100))
                setTypeface(typeface, 1)
            },
            LinearLayout.LayoutParams(0, dp(42), 1f)
        )
        val minimizeButton = Button(this).apply {
            text = "AIM"
            textSize = 11f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(4), 0, dp(4), 0)
        }
        header.addView(minimizeButton)
        root.addView(header)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(TextView(this).apply {
            text = "Game guide extension"
            textSize = 13f
            setTextColor(Color.rgb(0, 125, 145))
            setTypeface(typeface, 1)
            setPadding(0, dp(6), 0, dp(4))
        })
        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(8))
            text = initialStatus()
        }
        body.addView(statusText)
        body.addView(TextView(this).apply {
            text = "Only the game's visible white aiming guides are extended to the cushion. No guide is drawn when the game guide is absent."
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(8))
        })
        body.addView(Button(this).apply {
            text = "STOP CAPTURE"
            setOnClickListener { stopSelf() }
        })
        root.addView(body)
        // Start as one compact movable floating control.
        body.visibility = View.GONE

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            dp(64), dp(48), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = dp(12)
        params.y = dp(90)
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        header.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(5)) dragged = true
                    if (dragged && body.visibility != View.VISIBLE) {
                        params.x = (startX + dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        windowManager.updateViewLayout(root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) minimizeButton.performClick()
                    true
                }
                else -> true
            }
        }
        minimizeButton.setOnClickListener {
            val expand = body.visibility != View.VISIBLE
            body.visibility = if (expand) View.VISIBLE else View.GONE
            minimizeButton.text = if (expand) "×" else "AIM"
            (header.getChildAt(0) as? TextView)?.text = "POOL AIM"
            params.width = dp(if (expand) 280 else 64)
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            windowManager.updateViewLayout(root, params)
        }
        windowManager.addView(root, params)
        panel = root
    }

    private fun initialStatus(): String = "Waiting for screen-capture frames."

    private fun refreshStatus() {
        val scene = processedScene
        val tableStatus = if (scene?.scene?.table != null) "found" else "not found"
        val ballCount = scene?.scene?.balls?.size ?: 0
        val guides = scene?.gameAimGuides?.size ?: 0
        val text = "Captured frames: $capturedFrameCount" +
            "\nProcessed frames: $processedFrameCount · errors: $captureErrorCount" +
            "\nAim guides detected: $guides" +
            "\n" + (scene?.diagnostic ?: "Waiting for game aiming guide.")
        statusText?.post { statusText?.text = text }
        statusBadge?.post { statusBadge?.invalidate() }
    }

    private inner class StatusBadgeView : View(this@OverlayCaptureService) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        @Volatile var scene: ProcessedPoolFrame? = null

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val current = scene ?: return
            val table = current.scene.table ?: return
            val cue = current.scene.balls.firstOrNull { it.ballId == BallIdentity.CUE } ?: return
            if (current.gameAimGuides.isEmpty()) return

            val inset = cue.radius * 0.55f
            val bounds = RectF(
                table.left + inset, table.top + inset,
                table.right - inset, table.bottom - inset
            )
            // Draw only extensions of white guides that the game itself rendered.
            current.gameAimGuides.forEach { direction ->
                val start = FloatPoint(
                    cue.center.x + direction.x * cue.radius * 0.92f,
                    cue.center.y + direction.y * cue.radius * 0.92f
                )
                val edge = rayToTableEdge(
                    start,
                    FloatPoint(start.x + direction.x, start.y + direction.y),
                    bounds
                )
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2).toFloat()
                paint.color = Color.GREEN
                paint.pathEffect = DashPathEffect(
                    floatArrayOf(dp(8).toFloat(), dp(5).toFloat()), 0f
                )
                canvas.drawLine(start.x, start.y, edge.x, edge.y, paint)
            }
            paint.pathEffect = null
        }

        private fun rayToTableEdge(origin: FloatPoint, through: FloatPoint, r: RectF): FloatPoint {
            val dx = through.x - origin.x
            val dy = through.y - origin.y
            if (abs(dx) + abs(dy) < 0.001f) return through
            var t = Float.POSITIVE_INFINITY
            if (dx > 0f) t = min(t, (r.right - origin.x) / dx)
            if (dx < 0f) t = min(t, (r.left - origin.x) / dx)
            if (dy > 0f) t = min(t, (r.bottom - origin.y) / dy)
            if (dy < 0f) t = min(t, (r.top - origin.y) / dy)
            if (!t.isFinite() || t < 0f) return through
            return FloatPoint(origin.x + dx * t, origin.y + dy * t)
        }
    }

    private fun ballColor(id: Int?): Int = when (BallIdentity.baseColorId(id ?: -1)) {
            1 -> Color.rgb(242, 193, 46)
            2 -> Color.rgb(29, 79, 192)
            3 -> Color.rgb(210, 56, 42)
            4 -> Color.rgb(90, 42, 136)
            5 -> Color.rgb(239, 122, 26)
            6 -> Color.rgb(23, 137, 75)
            7 -> Color.rgb(123, 30, 36)
            else -> Color.YELLOW
        }
    }

    private fun makeNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification =
        if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Pool Aim · 8 Ball Pool recognition")
                .setContentText("Screen capture and ball recognition are active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Pool Aim · 8 Ball Pool recognition")
                .setContentText("Screen capture and ball recognition are active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }

    override fun onDestroy() {
        try { statusBadge?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        try { panel?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        statusBadge = null
        panel = null
        statusText = null
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.stop()
        projection = null
        reader?.close()
        reader = null
        latestFrames.clear()
        captureHandler = null
        visionHandler = null
        captureThread?.quitSafely()
        captureThread = null
        visionThread?.quitSafely()
        visionThread = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
