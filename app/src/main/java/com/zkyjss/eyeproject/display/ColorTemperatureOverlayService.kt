package com.zkyjss.eyeproject.display

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.zkyjss.eyeproject.MainActivity
import com.zkyjss.eyeproject.core.data.ColorTemperaturePreferences
import com.zkyjss.eyeproject.core.model.ColorTemperatureMode
import com.zkyjss.eyeproject.core.notifications.NotificationChannels
import com.zkyjss.eyeproject.core.notifications.Notifier
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ColorTemperatureOverlayService : Service() {
    companion object {
        private const val ACTION_SET_MODE = "com.zkyjss.eyeproject.action.SET_COLOR_TEMPERATURE"
        private const val EXTRA_MODE = "mode"

        fun createIntent(context: Context, mode: ColorTemperatureMode): Intent {
            return Intent(context, ColorTemperatureOverlayService::class.java).apply {
                action = ACTION_SET_MODE
                putExtra(EXTRA_MODE, mode.key)
            }
        }
    }

    @Inject
    lateinit var notifier: Notifier

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = if (intent == null) {
            ColorTemperaturePreferences.getMode(this)
        } else {
            ColorTemperatureMode.fromKey(intent.getStringExtra(EXTRA_MODE))
        }
        if (mode == ColorTemperatureMode.OFF) {
            ColorTemperaturePreferences.setMode(this, ColorTemperatureMode.OFF)
            removeOverlay()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            ColorTemperaturePreferences.setMode(this, ColorTemperatureMode.OFF)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = notifier.buildColorTemperatureNotification(
            mode = mode,
            openAppIntent = Intent(this, MainActivity::class.java),
            stopIntent = createIntent(this, ColorTemperatureMode.OFF)
        )
        startForeground(NotificationChannels.COLOR_TEMPERATURE_NOTIFICATION_ID, notification)
        ColorTemperaturePreferences.setMode(this, mode)
        showOverlay(mode)
        return START_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay(mode: ColorTemperatureMode) {
        val currentView = overlayView
        if (currentView != null) {
            currentView.setBackgroundColor(mode.overlayColor)
            val params = currentView.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                params.alpha = mode.windowOpacity
                windowManager.updateViewLayout(currentView, params)
            }
            return
        }

        val view = View(this).apply {
            setBackgroundColor(mode.overlayColor)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = mode.windowOpacity
            title = "EyeProtect color temperature filter"
        }
        windowManager.addView(view, params)
        overlayView = view
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        overlayView = null
    }
}
