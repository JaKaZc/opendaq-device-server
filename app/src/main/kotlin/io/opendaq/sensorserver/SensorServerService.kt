package io.opendaq.sensorserver

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import io.opendaq.androiddevice.GnssTime
import io.opendaq.androiddevice.PhoneStatus
import io.opendaq.androiddevice.UsbReader
import java.io.File

/// Runs openDAQ and its server in the background, as a foreground service.
///
/// Android lets a foreground service keep the microphone, the sensors and the
/// network while the app is closed and the screen is off, as long as it shows
/// a notification. The service also holds a CPU wake lock, so the sensors keep
/// delivering, a Wi-Fi lock, so the network stays up, and the multicast lock
/// for the mDNS announcement.
class SensorServerService : Service() {
    companion object {
        const val ACTION_STOP = "io.opendaq.sensorserver.STOP"
        const val ACTION_QUIT = "io.opendaq.sensorserver.QUIT"
        private const val CHANNEL = "server"
        private const val NOTIFICATION = 1
        private const val TAG = "SensorServer"

        /// Why the last start failed, for the control screen, or null.
        @Volatile var lastError: String? = null

        /// Whether the service runs; the server may still be starting.
        @Volatile var active = false

        private const val PREFERENCES = "server"
        private const val INCLUDE_CAMERA = "includeCamera"

        /// Whether the server also serves the camera. Off by default, because a
        /// running camera keeps Android's privacy indicator on and costs battery.
        fun includesCamera(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(INCLUDE_CAMERA, false)

        fun setIncludesCamera(context: Context, include: Boolean) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(INCLUDE_CAMERA, include).apply()
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SensorServerService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SensorServerService::class.java).setAction(ACTION_STOP))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    // Quit from the notification: the app's process ends once openDAQ stopped.
    private var quitting = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var started = false
    // GNSS time for the phone device's clock, while its TimeSource asks for it.
    private var gnssTime: GnssTime? = null
    // The phone's state for the phone device's System and USB channels.
    private var phoneStatus: PhoneStatus? = null
    // Cameras, serial devices and input devices on the USB port.
    private var usbReader: UsbReader? = null
    private var microphone = false
    private var camera = false
    private var location = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent?.action == ACTION_QUIT) {
            quitting = intent.action == ACTION_QUIT
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true
        active = true
        lastError = null

        val mayRecord = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val mayFilm = includesCamera(this) && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        microphone = mayRecord
        camera = mayFilm
        goForeground(notification(getString(R.string.starting)))
        holdLocks()

        // Starting openDAQ and its sensors takes a second or two, so it runs
        // off the main thread.
        val devices = arrayOf(NativeServer.phoneSensors(microphone = mayRecord, camera = mayFilm))
        val moduleDirectory = File(filesDir, "modules").apply { mkdirs() }.path
        val identity = Phone.identity(this)
        Thread {
            val error = NativeServer.start(moduleDirectory, NativeServer.modules, devices, NativeServer.functionBlocks, identity, NativeServer.PORT)
            handler.post {
                if (error != null) {
                    Log.e(TAG, "openDAQ could not start: $error")
                    lastError = error
                    stopSelf()
                } else {
                    refreshNotification()
                    phoneStatus = PhoneStatus(this).apply { start() }
                    usbReader = UsbReader(this).apply { start() }
                    gnssTime = GnssTime(this).apply {
                        // A foreground service gets the location in the
                        // background only with the location among its types.
                        beforeListening = {
                            if (!location) {
                                location = true
                                goForeground(notification(statusText()))
                            }
                        }
                        start()
                    }
                }
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (microphone) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (camera) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (location) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIFICATION, notification, types)
        } else {
            startForeground(NOTIFICATION, notification)
        }
    }

    private fun holdLocks() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openDAQ:SensorServer").apply { acquire() }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(mode, "openDAQ:SensorServer").apply { acquire() }
        multicastLock = wifi.createMulticastLock("openDAQ:SensorServer").apply { acquire() }
    }

    /// The notification says what is served where, and how many clients are
    /// connected. It is refreshed every few seconds.
    private fun refreshNotification() {
        if (!active) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(statusText()))
        handler.postDelayed({ refreshNotification() }, 5000)
    }

    private fun statusText(): String {
        val status = NativeServer.currentStatus()
        val address = Phone.connectionStrings().firstOrNull() ?: getString(R.string.no_network)
        val clients = resources.getQuantityString(R.plurals.clients, status.clients, status.clients)
        return resources.getQuantityString(R.plurals.streaming, status.sensors.size, status.sensors.size, address) + " " + clients
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.channel_description)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SensorServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val quit = PendingIntent.getService(
            this, 2, Intent(this, SensorServerService::class.java).setAction(ACTION_QUIT), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.quit), quit).build())
            .build()
    }

    override fun onDestroy() {
        active = false
        started = false
        handler.removeCallbacksAndMessages(null)
        gnssTime?.stop()
        gnssTime = null
        phoneStatus?.stop()
        phoneStatus = null
        usbReader?.stop()
        usbReader = null
        location = false
        // Stopping closes the clients' connections. It runs here rather than on
        // a thread of its own, so a start right after it cannot overlap.
        NativeServer.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        multicastLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
        if (quitting) Process.killProcess(Process.myPid())
    }
}
