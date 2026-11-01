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
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
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
    private var guideParams: WindowManager.LayoutParams? = null
    private var aimOn = false
    private var editMode = false
    private var pocketLines = true
    private var singleLine = true
    private var bankLine = true
    private var doubleBank = true
    private var threeLines = true
    private var ballEdges = true
    private var autoLengthen = true
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
                    if (bitmap != null) {
                        analysisExecutor.execute {
                            try {
                                val result = frameAnalyzer.analyze(bitmap)
                                if (result != null) {
                                    analyzedFrames++
                                    if (analyzedFrames % 60L == 0L) {
                                        Log.d("PoolAimCapture", "Preprocessed $analyzedFrames frames; latest=${result.processingTimeMs}ms")
                                    }
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
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
        wm.addView(v, lp); guide = v; guideParams = lp
    }

    private fun updateTouchMode() {
        val lp = guideParams ?: return
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            if (editMode) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { guide?.let { wm.updateViewLayout(it, lp) } } catch (_: Exception) {}
        guide?.invalidate()
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
        root.addView(Button(this).apply {
            text = "EDIT BALL POSITIONS: OFF"
            setOnClickListener { editMode = !editMode; text = if (editMode) "EDIT BALL POSITIONS: ON" else "EDIT BALL POSITIONS: OFF"; updateTouchMode() }
        })
        root.addView(section("Guideline Settings"))
        switch(root, "Auto Lengthen Guideline", true) { autoLengthen = it; guide?.invalidate() }
        root.addView(section("Premium Features"))
        switch(root, "Cushion Shot Guideline", true) { bankLine = it; guide?.invalidate() }
        switch(root, "Double Cushion Guideline", true) { doubleBank = it; guide?.invalidate() }
        switch(root, "Show 6 Pocket Lines", true) { pocketLines = it; guide?.invalidate() }
        switch(root, "Show 3 Lines", true) { threeLines = it; guide?.invalidate() }
        switch(root, "Show Edges of Ball", true) { ballEdges = it; guide?.invalidate() }
        switch(root, "Single Aim Line", true) { singleLine = it; guide?.invalidate() }
        root.addView(section("Guideline Style"))
        val styles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Thin", "Normal", "Thick").forEachIndexed { i, s ->
            styles.addView(Button(this).apply { text = s; setOnClickListener { thickness = listOf(1.5f,3f,6f)[i]; guide?.invalidate() } },
                LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        root.addView(styles)
        root.addView(TextView(this).apply {
            text = "Automatic recognition is being built. Screen frames are processed locally with OpenCV; aiming lines stay hidden until real ball detections are available."
            textSize = 12f; setTextColor(Color.DKGRAY); setPadding(0, dp(6), 0, dp(6))
        })
        root.addView(Button(this).apply { text = "RESET POSITIONS"; setOnClickListener { guide?.resetPoints() } })
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
        private var cue = PointF(.30f,.50f)
        private var obj = PointF(.68f,.50f)
        private var drag = 0
        private val radius get() = min(width,height)*.014f
        private fun pt(v: PointF) = PointF(table.left+v.x*table.width(),table.top+v.y*table.height())
        private fun pockets() = listOf(PointF(table.left,table.top),PointF(table.centerX(),table.top),PointF(table.right,table.top),
            PointF(table.left,table.bottom),PointF(table.centerX(),table.bottom),PointF(table.right,table.bottom))
        private fun dist(a:PointF,b:PointF)=hypot(a.x-b.x,a.y-b.y)
        private fun drawLine(c:Canvas,a:PointF,b:PointF,color:Int,w:Float=thickness) {
            paint.color=color; paint.strokeWidth=w; paint.style=Paint.Style.STROKE; paint.strokeCap=Paint.Cap.ROUND
            c.drawLine(a.x,a.y,b.x,b.y,paint)
        }
        private fun ball(c:Canvas,a:PointF,color:Int) {
            paint.style=Paint.Style.FILL; paint.color=color; c.drawCircle(a.x,a.y,radius,paint)
            if(ballEdges) { paint.style=Paint.Style.STROKE; paint.strokeWidth=2f; paint.color=Color.WHITE; c.drawCircle(a.x,a.y,radius,paint) }
        }
        fun resetPoints(){cue=PointF(.30f,.50f);obj=PointF(.68f,.50f);invalidate()}
        override fun onDraw(c:Canvas) {
            super.onDraw(c)
            table.set(width*.045f,height*.16f,width*.955f,height*.84f)
            if(!aimOn || !hasLiveDetection)return
            val a=pt(cue); val b=pt(obj); val ps=pockets()
            paint.style=Paint.Style.STROKE; paint.color=Color.WHITE; paint.strokeWidth=dp(2).toFloat()
            c.drawRoundRect(table,radius,radius,paint)
            ps.forEach { paint.color=Color.WHITE; paint.strokeWidth=1f; c.drawCircle(it.x,it.y,radius*1.15f,paint) }
            if(pocketLines) ps.forEach { drawLine(c,b,it,0x88FFFFFF.toInt(),maxOf(1f,thickness*.5f)) }
            val pocket=ps.minByOrNull{dist(b,it)} ?: ps.last()
            if(singleLine) {
                val dx=b.x-a.x; val dy=b.y-a.y; val len=hypot(dx,dy).coerceAtLeast(1f)
                val ux=dx/len; val uy=dy/len
                val ghost=PointF(b.x-ux*radius*2,b.y-uy*radius*2)
                if(threeLines){drawLine(c,a,ghost,Color.CYAN);drawLine(c,ghost,b,Color.YELLOW);drawLine(c,b,pocket,Color.GREEN)}
                else {drawLine(c,a,b,Color.CYAN);drawLine(c,b,pocket,Color.GREEN)}
                if(autoLengthen) drawLine(c,b,PointF(b.x+ux*table.width(),b.y+uy*table.width()),0x88FFFF00.toInt(),maxOf(1f,thickness*.7f))
            }
            if(bankLine||doubleBank) ps.forEach { target ->
                var x=b.x; var y=b.y; var vx=target.x-b.x; var vy=target.y-b.y
                repeat(if(doubleBank)2 else 1){ index ->
                    val hits=mutableListOf<Pair<Float,Int>>()
                    if(vx>0.0001f)hits.add(Pair((table.right-x)/vx,0))
                    if(vx< -0.0001f)hits.add(Pair((table.left-x)/vx,0))
                    if(vy>0.0001f)hits.add(Pair((table.bottom-y)/vy,1))
                    if(vy< -0.0001f)hits.add(Pair((table.top-y)/vy,1))
                    val hit=hits.filter{it.first>0.0001f}.minByOrNull{it.first} ?: return@repeat
                    val end=PointF(x+vx*hit.first,y+vy*hit.first)
                    if((index==0&&bankLine)||(index>0&&doubleBank))drawLine(c,PointF(x,y),end,if(index==0)Color.MAGENTA else Color.CYAN)
                    if(hit.second==0)vx= -vx else vy= -vy
                    x=end.x;y=end.y
                }
            }
            ball(c,a,Color.rgb(245,245,245)); ball(c,b,Color.rgb(235,175,40))
            paint.style=Paint.Style.FILL;paint.color=Color.BLACK;paint.textSize=dp(10).toFloat()
            c.drawText("CUE",a.x-dp(10),a.y+dp(4),paint);c.drawText("BALL",b.x-dp(12),b.y+dp(4),paint)
            if(editMode){paint.style=Paint.Style.STROKE;paint.color=Color.BLUE;paint.strokeWidth=dp(2).toFloat();c.drawCircle(a.x,a.y,radius*1.7f,paint);c.drawCircle(b.x,b.y,radius*1.7f,paint)}
        }
        override fun onTouchEvent(e:MotionEvent):Boolean {
            if(!editMode)return false
            val a=pt(cue);val b=pt(obj)
            when(e.action){
                MotionEvent.ACTION_DOWN->{drag=when{dist(PointF(e.x,e.y),a)<radius*2.5f->1;dist(PointF(e.x,e.y),b)<radius*2.5f->2;else->0};return drag!=0}
                MotionEvent.ACTION_MOVE->{val x=((e.x-table.left)/table.width()).coerceIn(0f,1f);val y=((e.y-table.top)/table.height()).coerceIn(0f,1f);if(drag==1)cue=PointF(x,y);if(drag==2)obj=PointF(x,y);invalidate();return drag!=0}
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{val used=drag!=0;drag=0;return used}
            }
            return true
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
