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
        val minimizeButton = Button(this).apply { text = "DETAILS" }
        header.addView(minimizeButton)
        root.addView(header)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(TextView(this).apply {
            text = "Full-ball recognition · solids · stripes · eight ball · cue ball"
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
            text = "Extended cue guide reaches the cushion; object-ball routes show direct or one-cushion bank paths. Geometry only—spin, friction and power are not simulated."
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(8))
        })
        body.addView(Button(this).apply {
            text = "STOP CAPTURE"
            setOnClickListener { stopSelf() }
        })
        root.addView(body)
        // Start collapsed so the overlay does not hide the table or cue stick.
        body.visibility = View.GONE

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            dp(190), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = dp(10)
        params.y = dp(12)
        minimizeButton.setOnClickListener {
            val expand = body.visibility != View.VISIBLE
            body.visibility = if (expand) View.VISIBLE else View.GONE
            minimizeButton.text = if (expand) "MINIMIZE" else "DETAILS"
            (header.getChildAt(0) as? TextView)?.text =
                if (expand) "POOL AIM · BALL IDS 0–15" else "POOL AIM"
            params.width = dp(if (expand) 330 else 190)
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
        val ids = scene?.scene?.balls?.filter { it.ballId != null }
            ?.joinToString(",") { it.ballId.toString() } ?: "none"
        val cue = scene?.scene?.balls?.count { it.ballId == BallIdentity.CUE } ?: 0
        val solutions = scene?.solutions?.size ?: 0
        val text = "Captured frames: $capturedFrameCount" +
            "\nProcessed frames: $processedFrameCount · errors: $captureErrorCount" +
            "\nSampling cap: 20 FPS (latest-frame worker)" +
            "\nTable: $tableStatus · ball candidates: $ballCount" +
            "\nIDs: $ids" +
            "\nCue candidates: $cue · collision-clear options: $solutions" +
            "\n" + (scene?.diagnostic ?: "Waiting for detector.")
        statusText?.post { statusText?.text = text }
        statusBadge?.post {
            statusBadge?.frameCount = capturedFrameCount
            statusBadge?.invalidate()
        }
    }

    private inner class StatusBadgeView : View(this@OverlayCaptureService) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var frameCount: Long = 0L
        @Volatile var scene: ProcessedPoolFrame? = null

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val current = scene
            val table = current?.scene?.table
            if (table != null) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2).toFloat()
                paint.color = Color.CYAN
                canvas.drawRect(table.left, table.top, table.right, table.bottom, paint)
                current.scene.balls.forEach { ball ->
                    paint.style = Paint.Style.FILL
                    paint.color = when (ball.kind) {
                        BallKind.CUE -> Color.WHITE
                        BallKind.EIGHT -> Color.BLACK
                        BallKind.STRIPE, BallKind.SOLID -> ballColor(ball.ballId)
                        BallKind.UNKNOWN -> Color.YELLOW
                    }
                    canvas.drawCircle(ball.center.x, ball.center.y, ball.radius, paint)
                    paint.style = Paint.Style.STROKE
                    paint.color = Color.WHITE
                    canvas.drawCircle(ball.center.x, ball.center.y, ball.radius, paint)
                    if (ball.ballId != null) {
                        paint.style = Paint.Style.FILL
                        paint.color = if (ball.kind == BallKind.EIGHT) Color.WHITE else Color.BLACK
                        paint.textSize = maxOf(dp(8).toFloat(), ball.radius * 0.85f)
                        val label = ball.ballId.toString()
                        canvas.drawText(
                            label,
                            ball.center.x - paint.measureText(label) / 2f,
                            ball.center.y + paint.textSize * 0.33f,
                            paint
                        )
                    }
                }
                val inset = maxOf(
                    dp(2).toFloat(),
                    current.scene.balls.map { it.radius }.average().takeIf { it.isFinite() }?.toFloat() ?: dp(8).toFloat()
                )
                val bounds = RectF(
                    table.left + inset, table.top + inset,
                    table.right - inset, table.bottom - inset
                )

                // Live cue-stick extensions are independent of shot solving.
                // Draw them as soon as table + cue + shaft axis are detected.
                val detectedCue = current.scene.balls.firstOrNull { it.ballId == BallIdentity.CUE }
                val liveAxis = current.cueStickAxis
                if (detectedCue != null && liveAxis != null) {
                    val liveCenter = detectedCue.center
                    val ends = lineToTableBounds(liveCenter, liveAxis, bounds)
                    if (ends != null) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = dp(2).toFloat()
                        paint.color = Color.GREEN
                        paint.pathEffect = DashPathEffect(floatArrayOf(dp(8).toFloat(), dp(5).toFloat()), 0f)
                        canvas.drawLine(ends.first.x, ends.first.y, liveCenter.x, liveCenter.y, paint)
                        canvas.drawLine(liveCenter.x, liveCenter.y, ends.second.x, ends.second.y, paint)
                        paint.pathEffect = null
                    }
                }

                val solution = current.solutions.firstOrNull()
                if (solution != null) {
                    val transform = CalibratedTableTransform(table)
                    val cue = transform.toScreen(solution.cue)
                    val ghost = transform.toScreen(solution.ghost)
                    val target = transform.toScreen(solution.objectTarget)
                    val pocket = transform.toScreen(solution.pocket)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(2).toFloat()
                    paint.color = Color.YELLOW
                    canvas.drawLine(ghost.x, ghost.y, target.x, target.y, paint)
                    if (solution.bankPoint != null) {
                        val bank = transform.toScreen(solution.bankPoint)
                        paint.color = Color.MAGENTA
                        canvas.drawLine(target.x, target.y, bank.x, bank.y, paint)
                        canvas.drawLine(bank.x, bank.y, pocket.x, pocket.y, paint)
                        paint.style = Paint.Style.FILL
                        paint.color = Color.WHITE
                        canvas.drawCircle(bank.x, bank.y, dp(4).toFloat(), paint)
                    } else {
                        paint.color = Color.CYAN
                        canvas.drawLine(target.x, target.y, pocket.x, pocket.y, paint)
                    }
                    paint.style = Paint.Style.FILL
                    paint.color = Color.GREEN
                    canvas.drawCircle(ghost.x, ghost.y, dp(4).toFloat(), paint)
                }
            }
            val left = dp(12).toFloat()
            val top = dp(12).toFloat()
            val badgeWidth = dp(236).toFloat()
            val badgeHeight = dp(34).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = 0xcc17232d.toInt()
            canvas.drawRoundRect(
                RectF(left, top, left + badgeWidth, top + badgeHeight),
                dp(8).toFloat(), dp(8).toFloat(), paint
            )
            paint.color = Color.WHITE
            paint.textSize = dp(12).toFloat()
            canvas.drawText("POOL AIM · IDS · $frameCount", left + dp(10), top + dp(22), paint)
        }

        /** Returns the two cushion intersections of an infinite line through origin. */
        private fun lineToTableBounds(
            origin: FloatPoint,
            axis: FloatPoint,
            r: RectF
        ): Pair<FloatPoint, FloatPoint>? {
            val magnitude = kotlin.math.hypot(axis.x, axis.y)
            if (!magnitude.isFinite() || magnitude < 0.001f) return null
            val dx = axis.x / magnitude
            val dy = axis.y / magnitude
            var low = Float.NEGATIVE_INFINITY
            var high = Float.POSITIVE_INFINITY
            if (abs(dx) > 0.0001f) {
                val a = (r.left - origin.x) / dx
                val b = (r.right - origin.x) / dx
                low = maxOf(low, minOf(a, b))
                high = minOf(high, maxOf(a, b))
            } else if (origin.x !in r.left..r.right) return null
            if (abs(dy) > 0.0001f) {
                val a = (r.top - origin.y) / dy
                val b = (r.bottom - origin.y) / dy
                low = maxOf(low, minOf(a, b))
                high = minOf(high, maxOf(a, b))
            } else if (origin.y !in r.top..r.bottom) return null
            if (!low.isFinite() || !high.isFinite() || low >= high) return null
            return FloatPoint(origin.x + dx * low, origin.y + dy * low) to
                FloatPoint(origin.x + dx * high, origin.y + dy * high)
        }

        /** Intersects a forward ray with the first inset table/cushion edge. */
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
