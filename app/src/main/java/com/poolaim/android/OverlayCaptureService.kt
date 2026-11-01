package com.poolaim.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class OverlayCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "poolaim_capture"
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var overlay: LinearLayout? = null
    private var enabled = false
    private val switches = mutableListOf<Switch>()
    private var guidelineStyle = "Normal"
    private lateinit var windowManager: WindowManager

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification())
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data != null && projection == null) startCapture(resultCode, data)
        showOverlay()
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                reader?.close()
                reader = null
                stopSelf()
            }
        }, null)
        reader?.setOnImageAvailableListener({ source ->
            val frame = source.acquireLatestImage()
            frame?.close()
        }, null)
        projection?.createVirtualDisplay(
            "PoolAimScreenCapture", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, null
        )
        // User-approved capture only. Frames remain on-device; no network upload is implemented.
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 14f, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.rgb(45, 50, 58))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(7), 0, dp(7))
        }

    private fun addSwitch(parent: LinearLayout, title: String, initial: Boolean = true) {
        val item = Switch(this).apply {
            text = title
            textSize = 14f
            setTextColor(Color.rgb(45, 50, 58))
            isChecked = initial
            setPadding(dp(2), dp(5), dp(2), dp(5))
        }
        switches.add(item)
        parent.addView(item, LinearLayout.LayoutParams(-1, dp(44)))
    }

    private fun showOverlay() {
        if (overlay != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(220, 225, 230))
            }
            elevation = dp(10).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val toggle = Button(this).apply {
            text = "AIM: OFF"
            setOnClickListener {
                enabled = !enabled
                text = if (enabled) "AIM: ON" else "AIM: OFF"
            }
        }
        val settingsButton = Button(this).apply {
            text = "SETTINGS"
        }
        header.addView(toggle, LinearLayout.LayoutParams(0, dp(46), 1f))
        header.addView(settingsButton, LinearLayout.LayoutParams(-2, dp(46)))
        root.addView(header)

        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        settings.addView(label("Guideline Settings", 17f, true))
        addSwitch(settings, "Auto Lengthen Guideline", true)
        settings.addView(label("Premium Features", 15f, true).apply {
            setTextColor(Color.rgb(0, 128, 145))
        })
        addSwitch(settings, "Cushion Shot Guideline", true)
        addSwitch(settings, "Show 3 Lines", true)
        addSwitch(settings, "Show Edges of Ball", true)
        settings.addView(label("Guideline Style", 14f, true))

        val styles = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf("Thin", "Normal", "Thick").forEach { style ->
            val option = RadioButton(this).apply {
                text = style
                textSize = 13f
                isChecked = style == guidelineStyle
                setOnClickListener { guidelineStyle = style }
            }
            styles.addView(option, RadioGroup.LayoutParams(0, dp(44), 1f))
        }
        settings.addView(styles)

        settings.addView(label("Accuracy tip: For long-range bank or kick shots, use firm cue-ball contact to reduce unwanted follow-ball effect.", 12f))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val reset = Button(this).apply {
            text = "RESET"
            setOnClickListener {
                switches.forEach { it.isChecked = true }
                guidelineStyle = "Normal"
                (0 until styles.childCount).forEach { index ->
                    (styles.getChildAt(index) as? RadioButton)?.isChecked =
                        (styles.getChildAt(index) as? RadioButton)?.text == "Normal"
                }
            }
        }
        val close = Button(this).apply {
            text = "CLOSE"
            setOnClickListener { settings.visibility = android.view.View.GONE }
        }
        actions.addView(reset)
        actions.addView(close)
        settings.addView(actions)
        root.addView(settings)

        settingsButton.setOnClickListener {
            settings.visibility =
                if (settings.visibility == android.view.View.VISIBLE) android.view.View.GONE
                else android.view.View.VISIBLE
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        val windowType = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(150)
        }
        windowManager.addView(scroll, params)
        overlay = root
        // Keep the ScrollView as the actual attached window's parent for clean removal.
        root.tag = scroll
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Pool Aim Overlay")
                .setContentText("Screen capture is active until you stop it")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Pool Aim Overlay")
                .setContentText("Screen capture is active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }
    }

    override fun onDestroy() {
        overlay?.tag?.let { parent ->
            if (parent is android.view.View) windowManager.removeView(parent)
        }
        overlay = null
        projection?.stop()
        projection = null
        reader?.close()
        reader = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
