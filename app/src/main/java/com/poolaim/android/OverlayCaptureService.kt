package com.poolaim.android

import android.app.*
import android.content.Intent
import android.graphics.*
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import kotlin.math.min

class OverlayCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "poolaim_capture"
    }
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private lateinit var wm: WindowManager
    private var guide: GuideView? = null
    private var panel: LinearLayout? = null
    private var miniBubble: TextView? = null
    private val frameAnalyzer = FrameAnalyzer()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var captureThread: HandlerThread? = null
    @Volatile private var lastFrameNanos = 0L
    @Volatile private var analyzedFrames = 0L
    @Volatile private var hasLiveDetection = false
    @Volatile private var latestAnalysis: FrameAnalyzer.Result? = null
    @Volatile private var lastGoodDetectionNanos = 0L
    private val detectionGraceNanos = 450_000_000L
    private val analysisBusy = AtomicBoolean(false)
    private var aimOn = true
    private var pocketLines = true
    private var ballEdges = true
    private var thickness = 3f
    private val calibrationStore by lazy { PocketCalibrationStore(this) }
    private var calibrationOverlay: CalibrationView? = null

    override fun onCreate() { super.onCreate(); wm = getSystemService(WINDOW_SERVICE) as WindowManager; makeChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification())
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                   else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data != null && projection == null) startCapture(code, data)
        addGuide(); addPanel()
        return START_NOT_STICKY
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun startCapture(code: Int, data: Intent) {
        val m = resources.displayMetrics
        reader = ImageReader.newInstance(m.widthPixels.coerceAtLeast(1), m.heightPixels.coerceAtLeast(1), PixelFormat.RGBA_8888, 2)
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { reader?.close(); reader = null; stopSelf() }
        }, null)
        captureThread = HandlerThread("PoolAimFrameCapture").also { it.start() }
        reader?.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.nanoTime()
                if (now - lastFrameNanos >= 33_000_000L) {
                    lastFrameNanos = now
                    val bitmap = imageToBitmap(image)
                    if (bitmap != null && analysisBusy.compareAndSet(false, true)) {
                        analysisExecutor.execute {
                            try {
                                val result = frameAnalyzer.analyze(bitmap)
                                if (result != null) {
                                    val valid = result.tableBounds != null &&
                                        result.cueBall != null && result.cueAxis != null
                                    val nowNanos = System.nanoTime()
                                    if (valid) {
                                        latestAnalysis = result
                                        lastGoodDetectionNanos = nowNanos
                                        hasLiveDetection = true
                                    } else {
                                        // Keep the last reliable overlay briefly instead of
                                        // blinking off on a single missed/partial frame.
                                        hasLiveDetection =
                                            nowNanos - lastGoodDetectionNanos <= detectionGraceNanos
                                    }
                                    Handler(mainLooper).post { guide?.invalidate() }
                                    analyzedFrames++
                                    if (analyzedFrames % 60L == 0L) {
                                        Log.d("PoolAimCapture", "Preprocessed $analyzedFrames frames; latest=${result.processingTimeMs}ms; valid=$valid")
                                    }
                                }
                            } finally {
                                bitmap.recycle()
                                analysisBusy.set(false)
                            }
                        }
                    } else {
                        bitmap?.recycle()
                    }
                }
            } catch (t: Throwable) {
                Log.e("PoolAimCapture", "Could not process captured frame", t)
            } finally {
                image.close()
            }
        }, Handler(captureThread!!.looper))
        projection?.createVirtualDisplay("PoolAimCapture", m.widthPixels, m.heightPixels, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, null)
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        if (image.planes.isEmpty() || image.width <= 0 || image.height <= 0) return null
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowBytes = image.width * 4
        if (pixelStride != 4 || rowStride < rowBytes) return null

        // ImageReader buffers may include row padding, and the final row is not
        // guaranteed to expose a complete padded row. Copy only visible pixels.
        val source = plane.buffer.duplicate()
        val requiredLastByte = (image.height - 1L) * rowStride + rowBytes
        if (requiredLastByte > source.limit()) return null
        val packed = ByteBuffer.allocateDirect(rowBytes * image.height)
        val row = ByteArray(rowBytes)
        for (y in 0 until image.height) {
            source.position(y * rowStride)
            source.get(row, 0, rowBytes)
            packed.put(row)
        }
        packed.flip()
        return Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also {
            it.copyPixelsFromBuffer(packed)
        }
    }

    private fun addGuide() {
        if (guide != null) return
        val v = GuideView()
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(-1, -1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        wm.addView(v, lp); guide = v
    }

    private fun addPanel() {
        if (panel != null) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.LTGRAY)
            }
            elevation = dp(8).toFloat()
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = "POOL AIM"
            textSize = 16f
            setTextColor(Color.rgb(20, 80, 100))
            setTypeface(typeface, 1)
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        head.addView(Button(this).apply {
            text = if (aimOn) "AIM: ON" else "AIM: OFF"
            setOnClickListener {
                aimOn = !aimOn
                text = if (aimOn) "AIM: ON" else "AIM: OFF"
                guide?.invalidate()
            }
        })
        root.addView(head)
        root.addView(Button(this).apply {
            text = "MINIMIZE"
            setOnClickListener {
                root.visibility = View.GONE
                showMiniBubble()
            }
        })
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(dp(230), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.END
        lp.x = dp(10)
        lp.y = dp(90)
        wm.addView(root, lp)
        panel = root
    }

    /** Replace the expanded settings panel with a compact tap-to-restore bubble. */
    private fun showMiniBubble() {
        if (miniBubble != null) return
        val target = panel ?: return
        val bubble = TextView(this).apply {
            text = "POOL AIM"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, 1)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(0, 125, 145))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.WHITE)
            }
            elevation = dp(8).toFloat()
            setOnClickListener {
                try { wm.removeView(this) } catch (_: Exception) {}
                miniBubble = null
                target.visibility = View.VISIBLE
            }
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.END
        lp.x = dp(10)
        lp.y = dp(90)
        wm.addView(bubble, lp)
        miniBubble = bubble
    }

    private fun section(t: String) = TextView(this).apply { text = t; textSize = 14f; setTextColor(Color.rgb(0,125,145)); setTypeface(typeface,1); setPadding(0,dp(6),0,dp(2)) }
    private fun switch(parent: LinearLayout, t: String, initial: Boolean, cb: (Boolean)->Unit) {
        parent.addView(Switch(this).apply { text=t; textSize=13f; setTextColor(Color.DKGRAY); isChecked=initial; setOnCheckedChangeListener { _,v -> cb(v) } },
            LinearLayout.LayoutParams(-1, dp(38)))
    }

    private inner class GuideView : View(this@OverlayCaptureService) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!aimOn || !hasLiveDetection) return
            val result = latestAnalysis ?: return
            val bounds = result.tableBounds ?: return
            val cue = result.cueBall ?: return
            val axis = result.cueAxis ?: return

            val sx = width.toFloat() / result.frameWidth.coerceAtLeast(1)
            val sy = height.toFloat() / result.frameHeight.coerceAtLeast(1)
            val left = bounds.left * sx
            val right = bounds.right * sx
            val top = bounds.top * sy
            val bottom = bounds.bottom * sy
            val cx = cue.x * sx
            val cy = cue.y * sy

            // Scale the detected frame-space vector into overlay-space coordinates.
            var dx = axis.first * sx
            var dy = axis.second * sy
            val norm = kotlin.math.hypot(dx, dy)
            if (norm < 0.0001f) return
            dx /= norm
            dy /= norm

            // Intersect the single cue-centred axis with the table rectangle.
            var tMin = Float.NEGATIVE_INFINITY
            var tMax = Float.POSITIVE_INFINITY
            if (kotlin.math.abs(dx) > 0.00001f) {
                val t1 = (left - cx) / dx
                val t2 = (right - cx) / dx
                tMin = maxOf(tMin, minOf(t1, t2))
                tMax = minOf(tMax, maxOf(t1, t2))
            } else if (cx !in left..right) return
            if (kotlin.math.abs(dy) > 0.00001f) {
                val t1 = (top - cy) / dy
                val t2 = (bottom - cy) / dy
                tMin = maxOf(tMin, minOf(t1, t2))
                tMax = minOf(tMax, maxOf(t1, t2))
            } else if (cy !in top..bottom) return
            if (!tMin.isFinite() || !tMax.isFinite() || tMin >= tMax) return

            paint.style = Paint.Style.STROKE
            paint.color = Color.CYAN
            paint.strokeWidth = maxOf(dp(3).toFloat(), thickness)
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(cx + dx * tMin, cy + dy * tMin, cx + dx * tMax, cy + dy * tMax, paint)
        }
    }

    private fun startPocketCalibration() {
        if (calibrationOverlay != null) return
        // Clear the entire game area before calibration: hide both the large
        // settings panel and its bubble so neither can cover a corner pocket.
        panel?.visibility = View.GONE
        miniBubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        miniBubble = null
        val view = CalibrationView()
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            -1, -1, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        try {
            wm.addView(view, lp)
            calibrationOverlay = view
        } catch (t: Throwable) {
            Log.e("PoolAimCalibration", "Unable to show calibration overlay", t)
            panel?.visibility = View.VISIBLE
        }
    }

    /** Touch-through is intentionally disabled during calibration so pocket taps are captured. */
    private inner class CalibrationView : View(this@OverlayCaptureService) {
        private val points = ArrayList<PointF>(6)
        private val names = listOf("Top-left", "Top-middle", "Top-right", "Bottom-right", "Bottom-middle", "Bottom-left")
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Keep the live table bright and visible while collecting pocket taps.
            canvas.drawColor(0x22000000.toInt())
            p.color = Color.WHITE
            p.textAlign = Paint.Align.CENTER
            p.textSize = dp(18).toFloat()
            canvas.drawText("POCKET CALIBRATION", width / 2f, dp(54).toFloat(), p)
            p.textSize = dp(11).toFloat()
            canvas.drawText("CANCEL", width - dp(42).toFloat(), dp(54).toFloat(), p)
            p.textSize = dp(14).toFloat()
            val next = points.size.coerceAtMost(5)
            canvas.drawText(
                if (points.size < 6) "Tap ${names[next]} pocket  (${points.size + 1}/6)" else "Calibration saved",
                width / 2f, dp(82).toFloat(), p
            )
            points.forEachIndexed { i, point ->
                val x = point.x * width
                val y = point.y * height
                p.style = Paint.Style.STROKE
                p.strokeWidth = dp(3).toFloat()
                p.color = Color.CYAN
                canvas.drawCircle(x, y, dp(17).toFloat(), p)
                p.style = Paint.Style.FILL
                p.textSize = dp(13).toFloat()
                canvas.drawText((i + 1).toString(), x, y - dp(24).toFloat(), p)
            }
            p.textSize = dp(12).toFloat()
            p.color = Color.WHITE
            canvas.drawText("Tap each pocket centre in order: TL, TM, TR, BR, BM, BL", width / 2f, height - dp(28).toFloat(), p)
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.action != android.view.MotionEvent.ACTION_UP) return true
            if (event.x > width - dp(82) && event.y < dp(76)) {
                cancelCalibration()
                return true
            }
            points.add(PointF((event.x / width).coerceIn(0f, 1f), (event.y / height).coerceIn(0f, 1f)))
            if (points.size == 6) {
                calibrationStore.save(points)
                finishCalibration()
            } else invalidate()
            return true
        }
    }

    private fun finishCalibration() {
        val view = calibrationOverlay ?: return
        try { wm.removeView(view) } catch (_: Exception) {}
        calibrationOverlay = null
        guide?.invalidate()
        panel?.let { root ->
            val button = (0 until root.childCount).map { root.getChildAt(it) }
                .filterIsInstance<Button>().firstOrNull { it.text.toString().contains("CALIBRATE") || it.text.toString().contains("RECALIBRATE") }
            button?.text = "RECALIBRATE POCKETS"
        }
        showMiniBubble()
        Toast.makeText(this, "Six pockets calibrated", Toast.LENGTH_SHORT).show()
    }

    private fun cancelCalibration() {
        val view = calibrationOverlay ?: return
        try { wm.removeView(view) } catch (_: Exception) {}
        calibrationOverlay = null
        showMiniBubble()
    }

    private fun makeChannel() {
        if(Build.VERSION.SDK_INT>=26){val ch=NotificationChannel(CHANNEL_ID,"Screen capture",NotificationManager.IMPORTANCE_LOW);getSystemService(NotificationManager::class.java).createNotificationChannel(ch)}
    }
    private fun notification():Notification = if(Build.VERSION.SDK_INT>=26)
        Notification.Builder(this,CHANNEL_ID).setContentTitle("Pool Aim Overlay").setContentText("Screen capture is active").setSmallIcon(android.R.drawable.ic_menu_view).build()
    else @Suppress("DEPRECATION") Notification.Builder(this).setContentTitle("Pool Aim Overlay").setContentText("Screen capture is active").setSmallIcon(android.R.drawable.ic_menu_view).build()

    override fun onDestroy() {
        try{guide?.let{wm.removeView(it)}}catch(_:Exception){}
        try{panel?.let{wm.removeView(it)}}catch(_:Exception){}
        try{miniBubble?.let{wm.removeView(it)}}catch(_:Exception){}
        try { calibrationOverlay?.let { wm.removeView(it) } } catch (_: Exception) {}
        calibrationOverlay=null
        guide=null;panel=null;miniBubble=null
        projection?.stop();projection=null
        reader?.close();reader=null
        captureThread?.quitSafely();captureThread=null
        analysisExecutor.shutdown()
        super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
