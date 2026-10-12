package dev.mobilespeak.mobilespeak

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.graphics.drawable.GradientDrawable

internal class FloatingPttService : Service() {
    private var windowManager: WindowManager? = null
    private var button: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var pressed = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WindowManager::class.java)
        val view = TextView(this).apply {
            text = "PTT"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF5865F2.toInt())
                setStroke(dp(2), 0xFFB8C0FF.toInt())
            }
            elevation = dp(8).toFloat()
            contentDescription = "Hold to talk"
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
        val layout = WindowManager.LayoutParams(
            dp(64), dp(64), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(18)
            y = dp(220)
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = layout.x; startY = layout.y
                    if (!pressed) {
                        pressed = true
                        v.scaleX = .94f; v.scaleY = .94f
                        v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF23A559.toInt()); setStroke(dp(2), Color.WHITE) }
                        ClientSession.setPushToTalkPressed(true)
                        runCatching {
                            androidx.core.content.ContextCompat.startForegroundService(
                                this, Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_START_CALL),
                            )
                        }
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = (startX - (event.rawX - downX).toInt()).coerceAtLeast(0)
                    layout.y = (startY + (event.rawY - downY).toInt()).coerceAtLeast(0)
                    runCatching { windowManager?.updateViewLayout(v, layout) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pressed) {
                        pressed = false
                        ClientSession.setPushToTalkPressed(false)
                        v.scaleX = 1f; v.scaleY = 1f
                        v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF5865F2.toInt()); setStroke(dp(2), 0xFFB8C0FF.toInt()) }
                    }
                    v.performClick()
                    true
                }
                else -> true
            }
        }
        button = view
        params = layout
        runCatching { windowManager?.addView(view, layout) }.onFailure { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (pressed) ClientSession.setPushToTalkPressed(false)
        pressed = false
        button?.let { view -> runCatching { windowManager?.removeView(view) } }
        button = null
        windowManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
