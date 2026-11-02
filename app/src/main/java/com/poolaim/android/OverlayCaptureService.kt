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
import kotlin.math.acos
import kotlin.math.hypot
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
                                        result.cueBall != null && result.balls.size >= 2
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
        private val table = RectF()
        private val radius get() = min(width,height)*.014f
        // Use saved calibration when available; otherwise estimate the six standard
        // pocket centres from the detected table rectangle so first launch is usable.
        private fun pockets(tableBounds: RectF): List<PointF> {
            val saved = calibrationStore.load()
            if (saved != null && saved.size == 6) {
                return saved.map { PointF(it.x * width, it.y * height) }
            }
            val inset = min(tableBounds.width(), tableBounds.height()) * 0.035f
            val left = tableBounds.left + inset
            val right = tableBounds.right - inset
            val top = tableBounds.top + inset
            val bottom = tableBounds.bottom - inset
            val middleX = (left + right) * 0.5f
            return listOf(
                PointF(left, top), PointF(middleX, top), PointF(right, top),
                PointF(right, bottom), PointF(middleX, bottom), PointF(left, bottom)
            )
        }
        private fun drawLine(c:Canvas,a:PointF,b:PointF,color:Int,w:Float=thickness) {
            paint.color=color; paint.strokeWidth=w; paint.style=Paint.Style.STROKE; paint.strokeCap=Paint.Cap.ROUND
            c.drawLine(a.x,a.y,b.x,b.y,paint)
        }
        private fun ball(c:Canvas,a:PointF,color:Int) {
            paint.style=Paint.Style.FILL; paint.color=color; c.drawCircle(a.x,a.y,radius,paint)
            if(ballEdges) { paint.style=Paint.Style.STROKE; paint.strokeWidth=2f; paint.color=Color.WHITE; c.drawCircle(a.x,a.y,radius,paint) }
        }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (!aimOn || !hasLiveDetection) return
            val result = latestAnalysis ?: return
            val bounds = result.tableBounds ?: return
            val cue = result.cueBall ?: return
            val sx = width.toFloat() / result.frameWidth.coerceAtLeast(1)
            val sy = height.toFloat() / result.frameHeight.coerceAtLeast(1)
            table.set(bounds.left * sx, bounds.top * sy, bounds.right * sx, bounds.bottom * sy)
            val cuePoint = PointF(cue.x * sx, cue.y * sy)
            val objects = result.balls.filterNot { it.isCueBall }
            if (objects.isEmpty()) return

            // Select one plausible cue-ball contact direction. Pocket geometry is
            // used only to choose the direction; no pocket, ball, contact or path
            // graphics are rendered.
            val inset = min(table.width(), table.height()) * 0.035f
            val left = table.left + inset
            val right = table.right - inset
            val top = table.top + inset
            val bottom = table.bottom - inset
            val midX = (left + right) * 0.5f
            val pockets = calibrationStore.load()?.takeIf { it.size == 6 }?.map {
                PointF(it.x * width, it.y * height)
            } ?: listOf(
                PointF(left, top), PointF(midX, top), PointF(right, top),
                PointF(right, bottom), PointF(midX, bottom), PointF(left, bottom)
            )

            var bestGhost: PointF? = null
            var bestScore = Float.POSITIVE_INFINITY
            val scale = (sx + sy) * 0.5f
            for (targetCandidate in objects) {
                val target = PointF(targetCandidate.x * sx, targetCandidate.y * sy)
                val contact = (cue.radius + targetCandidate.radius) * scale
                for (pocket in pockets) {
                    val dx = pocket.x - target.x
                    val dy = pocket.y - target.y
                    val length = hypot(dx, dy).coerceAtLeast(1f)
                    val ghost = PointF(target.x - dx / length * contact, target.y - dy / length * contact)
                    val cueDx = ghost.x - cuePoint.x
                    val cueDy = ghost.y - cuePoint.y
                    val cueDistance = hypot(cueDx, cueDy)
                    val score = cueDistance + length * 0.55f
                    if (score < bestScore) {
                        bestScore = score
                        bestGhost = ghost
                    }
                }
            }
            val ghost = bestGhost ?: return
            val dx = ghost.x - cuePoint.x
            val dy = ghost.y - cuePoint.y
            val length = hypot(dx, dy)
            if (length < 1f) return
            val ux = dx / length
            val uy = dy / length

            // Extend the same straight cue axis through the cue-ball centre
            // in both directions, ending at the two opposing table edges.
            fun edgePoint(dx: Float, dy: Float): PointF? {
                var t = Float.POSITIVE_INFINITY
                if (dx > 0.0001f) t = min(t, (table.right - cuePoint.x) / dx)
                if (dx < -0.0001f) t = min(t, (table.left - cuePoint.x) / dx)
                if (dy > 0.0001f) t = min(t, (table.bottom - cuePoint.y) / dy)
                if (dy < -0.0001f) t = min(t, (table.top - cuePoint.y) / dy)
                if (!t.isFinite() || t <= 0f) return null
                return PointF(cuePoint.x + dx * t, cuePoint.y + dy * t)
            }
            val forwardEdge = edgePoint(ux, uy) ?: return
            val backwardEdge = edgePoint(-ux, -uy) ?: return

            paint.style = Paint.Style.STROKE
            paint.color = Color.CYAN
            paint.strokeWidth = maxOf(dp(3).toFloat(), thickness)
            paint.strokeCap = Paint.Cap.ROUND
            // Two clean extensions; do not draw a marker over the cue ball.
            c.drawLine(backwardEdge.x, backwardEdge.y, cuePoint.x, cuePoint.y, paint)
            c.drawLine(cuePoint.x, cuePoint.y, forwardEdge.x, forwardEdge.y, paint)
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
