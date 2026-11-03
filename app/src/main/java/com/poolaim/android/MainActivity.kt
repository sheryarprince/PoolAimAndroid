package com.poolaim.android

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val captureRequest = 701
    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
        }
        layout.addView(TextView(this).apply {
            text = "POOL AIM · 8 BALL POOL\n\nRecognizes the original trainer's ball IDs 0–15: cue, solids, eight ball and stripes. Uses full-ball appearance sampling, blue/green table recognition and a ghost-ball solver with cue-path and object-path obstruction checks.\n\nCushion physics, spin, power and game-play accuracy still require device validation."
            textSize = 17f
        })
        layout.addView(Button(this).apply {
            text = "Grant floating-window permission"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
                }
            }
        })
        layout.addView(Button(this).apply {
            text = "Start 8 Ball Pool recognition"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
                } else {
                    startActivityForResult(projectionManager.createScreenCaptureIntent(), captureRequest)
                }
            }
        })
        layout.addView(Button(this).apply {
            text = "Stop capture"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayCaptureService::class.java)) }
        })
        setContentView(layout)
    }

    @Deprecated("Deprecated by Android; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, OverlayCaptureService::class.java).apply {
                putExtra(OverlayCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(OverlayCaptureService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(service)
        }
    }
}
