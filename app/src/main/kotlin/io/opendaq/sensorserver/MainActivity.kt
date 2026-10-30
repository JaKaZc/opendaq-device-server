package io.opendaq.sensorserver

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.opendaq.androiddevice.GnssTime

/// The control screen: Start and Stop, where to connect, and how many
/// clients are connected. The server itself runs in SensorServerService and
/// keeps running when this screen is closed.
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var state: TextView
    private lateinit var detail: TextView
    private lateinit var addresses: TextView
    private lateinit var dashboard: LinearLayout
    private lateinit var dashboardAddresses: TextView
    private lateinit var dashboardPin: TextView
    private lateinit var error: TextView
    private lateinit var toggle: Button
    private lateinit var camera: CheckBox
    private lateinit var quit: Button

    private val navy = Color.rgb(0x0B, 0x2E, 0x59)
    private val orange = Color.rgb(0xF2, 0x62, 0x32)
    private val muted = Color.rgb(0x5B, 0x67, 0x78)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        column.addView(text(getString(R.string.app_name), 24f, navy, bold = true))
        column.addView(text(getString(R.string.summary), 14f, muted).also { it.setPadding(0, dp(8), 0, dp(24)) })

        state = text("", 18f, navy, bold = true)
        detail = text("", 14f, muted).also { it.setPadding(0, dp(4), 0, dp(12)) }
        addresses = text("", 16f, navy).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.rgb(0xEE, 0xF1, 0xF5))
            }
        }
        error = text("", 14f, Color.rgb(0xB3, 0x26, 0x1E)).also { it.setPadding(0, dp(12), 0, 0) }
        toggle = Button(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            isAllCaps = false
            setOnClickListener { onToggle() }
        }
        column.addView(state)
        column.addView(detail)
        column.addView(addresses)

        // The Dashboard: where any browser shows the phone's signals, and the
        // PIN its page asks for before it changes a setting.
        dashboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(20), 0, 0)
        }
        dashboard.addView(text(getString(R.string.dashboard), 18f, navy, bold = true))
        dashboard.addView(text(getString(R.string.dashboard_detail), 14f, muted).also { it.setPadding(0, dp(4), 0, dp(8)) })
        dashboardAddresses = text("", 16f, navy).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.rgb(0xEE, 0xF1, 0xF5))
            }
        }
        dashboard.addView(dashboardAddresses)
        dashboardPin = text("", 18f, navy, bold = true).also { it.setPadding(0, dp(10), 0, 0) }
        dashboard.addView(dashboardPin)
        dashboard.visibility = View.GONE
        column.addView(dashboard)
        column.addView(error)
        camera = CheckBox(this).apply {
            text = getString(R.string.include_camera)
            textSize = 15f
            setTextColor(navy)
            isChecked = SensorServerService.includesCamera(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> SensorServerService.setIncludesCamera(this@MainActivity, checked) }
        }
        column.addView(camera, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        column.addView(text(getString(R.string.include_camera_detail), 13f, muted).also { it.setPadding(dp(32), 0, 0, 0) })
        column.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(24) })
        quit = Button(this).apply {
            text = getString(R.string.quit_app)
            textSize = 16f
            setTextColor(navy)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), navy)
            }
            setOnClickListener { onQuit() }
        }
        column.addView(quit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })
        column.addView(text(getString(R.string.background_note), 13f, muted).also { it.setPadding(0, dp(16), 0, 0) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(column)
        })
        // Dark status bar icons on the white screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(light, light)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun onToggle() {
        if (SensorServerService.active) {
            SensorServerService.stop(this)
            handler.postDelayed({ refresh() }, 300)
            return
        }
        // The microphone, the camera and the notification need the user's
        // permission. Without one of them, the other sensors are served.
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (camera.isChecked && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) startServer() else requestPermissions(missing.toTypedArray(), PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS) startServer()
    }

    /// Stops the server and closes the app, its process included, so nothing
    /// of it keeps running. The process ends once openDAQ has stopped, or
    /// after three seconds.
    private fun onQuit() {
        handler.removeCallbacksAndMessages(null)
        stopService(Intent(this, SensorServerService::class.java))
        finishAndRemoveTask()
        val main = Handler(Looper.getMainLooper())
        val deadline = SystemClock.uptimeMillis() + 3000
        fun end() {
            if (SensorServerService.active && SystemClock.uptimeMillis() < deadline) main.postDelayed({ end() }, 50)
            else Process.killProcess(Process.myPid())
        }
        main.post { end() }
    }

    private fun startServer() {
        SensorServerService.start(this)
        handler.postDelayed({ refresh() }, 300)
    }

    private fun refresh() {
        handler.removeCallbacksAndMessages(null)
        val status = NativeServer.currentStatus()
        val active = SensorServerService.active
        val connections = Phone.connectionStrings()
        when {
            status.running -> {
                state.text = resources.getQuantityString(R.plurals.serving, status.sensors.size, status.sensors.size)
                detail.text = status.sensors.joinToString(", ") + "\n" +
                    resources.getQuantityString(R.plurals.clients, status.clients, status.clients)
                addresses.text = if (connections.isEmpty()) getString(R.string.no_network) else connections.joinToString("\n")
                addresses.visibility = TextView.VISIBLE
                val board = status.dashboard
                if (board != null && board.addresses.isNotEmpty()) {
                    dashboardAddresses.text = board.addresses.joinToString("\n")
                    dashboardPin.text = if (board.pin.isEmpty()) getString(R.string.dashboard_no_pin) else getString(R.string.dashboard_pin, board.pin)
                    dashboard.visibility = View.VISIBLE
                } else {
                    dashboard.visibility = View.GONE
                }
            }
            active -> {
                dashboard.visibility = View.GONE
                state.text = getString(R.string.starting)
                detail.text = ""
                addresses.visibility = TextView.GONE
            }
            else -> {
                dashboard.visibility = View.GONE
                state.text = getString(R.string.stopped)
                detail.text = getString(R.string.stopped_detail)
                addresses.visibility = TextView.GONE
            }
        }
        error.text = SensorServerService.lastError?.let { getString(R.string.start_failed, it) } ?: ""
        toggle.text = getString(if (active) R.string.stop else R.string.start)
        // The camera setting applies when the server starts.
        camera.isEnabled = !active
        // A client chose GNSS time for the phone device; it needs the location.
        if (status.running) GnssTime.askIfWanted(this)
        toggle.background = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(if (active) navy else orange)
        }
        handler.postDelayed({ refresh() }, 1000)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        gravity = Gravity.START
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PERMISSIONS = 1
    }
}
