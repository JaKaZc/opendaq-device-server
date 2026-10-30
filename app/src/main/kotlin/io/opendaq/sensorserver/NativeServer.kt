package io.opendaq.sensorserver

import org.json.JSONObject

/// The openDAQ server in libsensor_server.so (native/server).
object NativeServer {
    init {
        System.loadLibrary("sensor_server")
    }

    /// The openDAQ modules the app carries: the phone's sensors, the native
    /// streaming server that serves them, and the Universal renderer
    /// (opendaq-universal-function-blocks).
    val modules = arrayOf(
        "libandroid_device_module.module.so",
        "libnative_stream_srv_module-64-${BuildConfig.OPENDAQ_MAJOR}.module.so",
        "libuniversal_function_blocks_module.module.so",
    )

    /// The phone's sensors, as the channels of AndroidDeviceModule's phone
    /// device. The options say whether the Microphone and Camera channels start
    /// active; a sensor the phone lacks has no channel.
    /// The device also adds a Dashboard, a web page of its main signals on
    /// port 8090, for any browser on the network.
    fun phoneSensors(microphone: Boolean, camera: Boolean) = "android://?Microphone=$microphone&Camera=$camera&Dashboard=true"

    /// Function blocks added to the phone's root device at start, by type,
    /// so a client finds them already there: the Universal renderer, whose
    /// input ports a client connects to the phone's signals and whose plot
    /// openDAQ Studio draws.
    val functionBlocks = arrayOf("UniversalRenderer")

    /// openDAQ's native protocol port.
    const val PORT = 7420

    /// Starts openDAQ and the server. `identity` is the phone's name, maker,
    /// model and serial number. Returns null, or the reason it failed.
    @JvmStatic external fun start(
        moduleDirectory: String,
        modules: Array<String>,
        devices: Array<String>,
        functionBlocks: Array<String>,
        identity: Array<String>,
        port: Int,
    ): String?

    @JvmStatic external fun stop()

    @JvmStatic private external fun status(): String

    /// The Dashboard: the addresses to open in a browser, and the PIN its page
    /// asks for before it changes a setting.
    data class Dashboard(val addresses: List<String>, val pin: String)

    data class Status(val running: Boolean, val sensors: List<String>, val clients: Int, val dashboard: Dashboard?)

    fun currentStatus(): Status {
        val json = JSONObject(status())
        val names = json.getJSONArray("sensors")
        return Status(
            json.getBoolean("running"),
            List(names.length()) { names.getString(it) },
            json.getInt("clients"),
            json.optJSONObject("dashboard")?.let { board ->
                Dashboard(
                    board.optString("address").split("\n").filter { it.startsWith("http") },
                    board.optString("pin"),
                )
            },
        )
    }
}
