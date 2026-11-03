package com.poolaim.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import com.poolaim.android.vision.PoolAimProcessor
import com.poolaim.android.vision.ProcessedPoolFrame
import com.poolaim.android.engine.CalibratedTableTransform

class OverlayCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "poolaim_capture"
        private const val CAPTURE_SAMPLE_INTERVAL_MS = 250L
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private lateinit var windowManager: WindowManager
    private var statusBadge: StatusBadgeView? = null
    private var panel: LinearLayout? = null
    private var statusText: TextView? = null
    private val latestFrames = LatestFrameMailbox()
    private val aimProcessor = PoolAimProcessor()
    @Volatile private var processedScene: ProcessedPoolFrame? = null
    private var capturedFrameCount = 0L
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

        if (resultData != null && projection == null) {
            startCapture(resultCode, resultData)
        }
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
            val image = try {
                source.acquireLatestImage()
            } catch (_: Exception) {
                null
            }

            if (image != null) {
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSampleAt >= CAPTURE_SAMPLE_INTERVAL_MS) {
                        lastSampleAt = now
                        val frame = CapturedRgbaFrame.copyFrom(image)
                        latestFrames.publish(frame)
                        processedScene = aimProcessor.process(frame)
                        statusBadge?.post { statusBadge?.scene = processedScene; statusBadge?.invalidate() }
                        capturedFrameCount++
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
                text = "POOL AIM · REA PORT"
                textSize = 15f
                setTextColor(Color.rgb(20, 80, 100))
                setTypeface(typeface, 1)
            },
            LinearLayout.LayoutParams(0, dp(42), 1f)
        )
        val minimizeButton = Button(this).apply { text = "MINIMIZE" }
        header.addView(minimizeButton)
        root.addView(header)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(
            TextView(this).apply {
                text = "Capture and confirmed mechanics scaffold"
                textSize = 14f
                setTextColor(Color.rgb(0, 125, 145))
                setTypeface(typeface, 1)
                setPadding(0, dp(6), 0, dp(4))
            }
        )
        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(8))
            text = initialStatus()
        }
        body.addView(statusText)
        body.addView(
            TextView(this).apply {
                text = "Aim lines are intentionally disabled. Binary-mask generation and native profile scoring are not yet recovered; no guessed pocket, bank-shot, or collision geometry is drawn."
                textSize = 12f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(4), 0, dp(8))
            }
        )
        body.addView(Button(this).apply {
            text = "STOP CAPTURE"
            setOnClickListener { stopSelf() }
        })
        root.addView(body)

        minimizeButton.setOnClickListener {
            val visible = body.visibility == View.VISIBLE
            body.visibility = if (visible) View.GONE else View.VISIBLE
            minimizeButton.text = if (visible) "SHOW DETAILS" else "MINIMIZE"
        }

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            dp(320),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = dp(10)
        params.y = dp(90)
        windowManager.addView(root, params)
        panel = root
    }

    private fun initialStatus(): String =
        "Waiting for screen-capture frames.\nMask and scoring stages are not connected."

    private fun refreshStatus() {
        val frameAvailability = if (latestFrames.peek() != null) "available" else "not available"
        val scene = processedScene
        val text = "Captured frames: " + capturedFrameCount +
            "\nCapture errors: " + captureErrorCount +
            "\nFrame sampling: up to 4 FPS (250 ms)." +
            "\nLatest raw RGBA frame: " + frameAvailability +
            "\nTable: " + if (scene?.scene?.table != null) "candidate found" else "not found" +
            "\nBall candidates: " + (scene?.scene?.balls?.size ?: 0) +
            "\nAim candidates: " + (scene?.solutions?.size ?: 0) +
            "\n" + (scene?.diagnostic ?: "Waiting for detector.")
        statusText?.post {
            statusText?.text = text
        }
        statusBadge?.post {
            statusBadge?.frameCount = capturedFrameCount
            statusBadge?.invalidate()
        }
    }

    private inner class StatusBadgeView : View(this@OverlayCaptureService) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var frameCount: Long = 0L
        var scene: ProcessedPoolFrame? = null

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val current = scene
            val table = current?.scene?.table
            if (table != null) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2).toFloat()
                paint.color = Color.CYAN
                canvas.drawRect(table.left, table.top, table.right, table.bottom, paint)
                paint.style = Paint.Style.FILL
                current.scene.balls.forEach { ball ->
                    paint.color = if (ball.isCueCandidate) Color.WHITE else Color.YELLOW
                    canvas.drawCircle(ball.center.x, ball.center.y, ball.radius, paint)
                }
                val solution = current.solutions.firstOrNull()
                if (solution != null) {
                    val transform = CalibratedTableTransform(table)
                    val cue = transform.toScreen(solution.cue)
                    val ghost = transform.toScreen(solution.ghost)
                    val target = transform.toScreen(solution.objectTarget)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(2).toFloat()
                    paint.color = Color.GREEN
                    canvas.drawLine(cue.x, cue.y, ghost.x, ghost.y, paint)
                    paint.color = Color.YELLOW
                    canvas.drawLine(ghost.x, ghost.y, target.x, target.y, paint)
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
                dp(8).toFloat(),
                dp(8).toFloat(),
                paint
            )
            paint.color = Color.WHITE
            paint.textSize = dp(12).toFloat()
            canvas.drawText(
                "POOL AIM · CAPTURE ONLY · " + frameCount,
                left + dp(10),
                top + dp(22),
                paint
            )
        }
    }

    private fun makeNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen capture",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification =
        if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Pool Aim REA Port")
                .setContentText("Raw screen capture is active; estimated aim lines are disabled")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Pool Aim REA Port")
                .setContentText("Raw screen capture is active; estimated aim lines are disabled")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }

    override fun onDestroy() {
        try {
            statusBadge?.let { windowManager.removeView(it) }
        } catch (_: Exception) {
        }
        try {
            panel?.let { windowManager.removeView(it) }
        } catch (_: Exception) {
        }
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
        captureThread?.quitSafely()
        captureThread = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
