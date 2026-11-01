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
    private val analysisBusy = AtomicBoolean(false)
    private var aimOn = false
    private var pocketLines = true
    private var ballEdges = true
    private var thickness = 3f

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
                                    latestAnalysis = result
                                    hasLiveDetection = result.tableBounds != null && result.cueBall != null && result.balls.size >= 2
                                    Handler(mainLooper).post { guide?.invalidate() }
                                    analyzedFrames++
                                    if (analyzedFrames % 60L == 0L) {
                                        Log.d("PoolAimCapture", "Preprocessed $analyzedFrames frames; latest=${result.processingTimeMs}ms")
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
        if (image.planes.isEmpty()) return null
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride != 4 || rowStride < image.width * pixelStride) return null
        val paddedWidth = rowStride / pixelStride
        val buffer: ByteBuffer = plane.buffer
        buffer.rewind()
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        if (cropped !== padded) padded.recycle()
        return cropped
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
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.LTGRAY) }
            elevation = dp(8).toFloat()
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply { text = "POOL AIM"; textSize = 16f; setTextColor(Color.rgb(20,80,100)); setTypeface(typeface, 1) },
            LinearLayout.LayoutParams(0, dp(44), 1f))
        head.addView(Button(this).apply { text = "AIM: ON"; setOnClickListener { aimOn = !aimOn; text = if (aimOn) "AIM: ON" else "AIM: OFF"; guide?.invalidate() } })
        root.addView(head)
        root.addView(section("Live Detection Display"))
        switch(root, "Show pocket reference lines", true) { pocketLines = it; guide?.invalidate() }
        switch(root, "Show ball outlines", true) { ballEdges = it; guide?.invalidate() }
        root.addView(section("Guideline Style"))
        val styles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Thin", "Normal", "Thick").forEachIndexed { i, s ->
            styles.addView(Button(this).apply { text = s; setOnClickListener { thickness = listOf(1.5f,3f,6f)[i]; guide?.invalidate() } },
                LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        root.addView(styles)
        root.addView(TextView(this).apply {
            text = "Live OpenCV table and ball proposals are processed on-device. Guides appear only when a table, cue candidate and object balls are detected. A trained pool-specific AI model is a separate next milestone."
            textSize = 12f; setTextColor(Color.DKGRAY); setPadding(0, dp(6), 0, dp(6))
        })
        root.addView(Button(this).apply { text = "MINIMIZE"; setOnClickListener { root.visibility = View.GONE; showMiniBubble() } })
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(dp(310), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.END; lp.x = dp(10); lp.y = dp(90)
        wm.addView(root, lp); panel = root
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
        private fun pockets() = listOf(PointF(table.left,table.top),PointF(table.centerX(),table.top),PointF(table.right,table.top),
            PointF(table.left,table.bottom),PointF(table.centerX(),table.bottom),PointF(table.right,table.bottom))
        private fun drawLine(c:Canvas,a:PointF,b:PointF,color:Int,w:Float=thickness) {
            paint.color=color; paint.strokeWidth=w; paint.style=Paint.Style.STROKE; paint.strokeCap=Paint.Cap.ROUND
            c.drawLine(a.x,a.y,b.x,b.y,paint)
        }
        private fun ball(c:Canvas,a:PointF,color:Int) {
            paint.style=Paint.Style.FILL; paint.color=color; c.drawCircle(a.x,a.y,radius,paint)
            if(ballEdges) { paint.style=Paint.Style.STROKE; paint.strokeWidth=2f; paint.color=Color.WHITE; c.drawCircle(a.x,a.y,radius,paint) }
        }
        override fun onDraw(c:Canvas) {
            super.onDraw(c)
            val result = latestAnalysis ?: return
            val bounds = result.tableBounds ?: return
            val sx = width.toFloat() / result.frameWidth.coerceAtLeast(1)
            val sy = height.toFloat() / result.frameHeight.coerceAtLeast(1)
            table.set(bounds.left * sx, bounds.top * sy, bounds.right * sx, bounds.bottom * sy)
            if (!aimOn || !hasLiveDetection) return

            val cue = result.cueBall ?: return
            val cuePoint = PointF(cue.x * sx, cue.y * sy)
            val viewRadius = cue.radius * ((sx + sy) * 0.5f)
            val ps = pockets()
            val objects = result.balls.filterNot { it.isCueBall }
            if (objects.isEmpty()) return

            paint.style = Paint.Style.STROKE
            paint.color = Color.WHITE
            paint.strokeWidth = dp(2).toFloat()
            c.drawRoundRect(table, radius, radius, paint)
            ps.forEach { p ->
                paint.color = Color.WHITE
                paint.strokeWidth = 1.5f
                c.drawCircle(p.x, p.y, viewRadius * 1.25f, paint)
            }

            objects.forEach { candidate ->
                val p = PointF(candidate.x * sx, candidate.y * sy)
                paint.style = Paint.Style.FILL
                paint.color = 0xFFFFB52E.toInt()
                c.drawCircle(p.x, p.y, viewRadius, paint)
                if (ballEdges) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2f
                    paint.color = Color.WHITE
                    c.drawCircle(p.x, p.y, viewRadius, paint)
                }
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            c.drawCircle(cuePoint.x, cuePoint.y, viewRadius, paint)
            paint.style = Paint.Style.STROKE
            paint.color = Color.CYAN
            paint.strokeWidth = 2f
            c.drawCircle(cuePoint.x, cuePoint.y, viewRadius * 1.35f, paint)

            data class CandidateShot(val cue: PointF, val ghost: PointF, val target: PointF, val pocket: PointF, val score: Float)
            fun pointSegmentDistance(p: PointF, a: PointF, b: PointF): Float {
                val dx = b.x - a.x
                val dy = b.y - a.y
                val denom = dx * dx + dy * dy
                if (denom <= 0.001f) return hypot(p.x - a.x, p.y - a.y)
                val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / denom).coerceIn(0f, 1f)
                return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
            }

            val shots = ArrayList<CandidateShot>()
            for (targetCandidate in objects) {
                val target = PointF(targetCandidate.x * sx, targetCandidate.y * sy)
                for (pocket in ps) {
                    val dx = pocket.x - target.x
                    val dy = pocket.y - target.y
                    val len = hypot(dx, dy).coerceAtLeast(1f)
                    val ux = dx / len
                    val uy = dy / len
                    val contactDistance = (cue.radius + targetCandidate.radius) * ((sx + sy) * 0.5f)
                    val ghost = PointF(target.x - ux * contactDistance, target.y - uy * contactDistance)
                    val blocked = objects.any { other ->
                        if (other === targetCandidate) false else {
                            val op = PointF(other.x * sx, other.y * sy)
                            pointSegmentDistance(op, cuePoint, ghost) < viewRadius * 1.85f ||
                                pointSegmentDistance(op, target, pocket) < viewRadius * 1.85f
                        }
                    }
                    if (blocked) continue
                    val cueDx = ghost.x - cuePoint.x
                    val cueDy = ghost.y - cuePoint.y
                    val cueDistance = hypot(cueDx, cueDy).coerceAtLeast(1f)
                    val alignment = ((cueDx * dx + cueDy * dy) / (cueDistance * len)).coerceIn(-1f, 1f)
                    val cutAngle = acos(alignment)
                    val score = cueDistance + len * 0.55f + cutAngle * viewRadius * 2.2f
                    shots.add(CandidateShot(cuePoint, ghost, target, pocket, score))
                }
            }
            val best = shots.minByOrNull { it.score }
            if (best != null) {
                drawLine(c, best.cue, best.ghost, Color.CYAN, thickness)
                drawLine(c, best.ghost, best.target, Color.YELLOW, thickness)
                drawLine(c, best.target, best.pocket, Color.GREEN, thickness)
                if (pocketLines) ps.forEach { drawLine(c, best.target, it, 0x44FFFFFF, maxOf(1f, thickness * .45f)) }
            }

            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = dp(11).toFloat()
            c.drawText("CV: ${result.balls.size} balls", table.left, table.top - dp(7).toFloat(), paint)
        }
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
        guide=null;panel=null;miniBubble=null
        projection?.stop();projection=null
        reader?.close();reader=null
        captureThread?.quitSafely();captureThread=null
        analysisExecutor.shutdown()
        super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
