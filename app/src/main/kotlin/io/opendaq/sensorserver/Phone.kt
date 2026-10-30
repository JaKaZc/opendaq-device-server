package io.opendaq.sensorserver

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

/// What the phone tells openDAQ clients about itself, and where they reach it.
object Phone {
    /// The phone's name as the user set it, its maker and model, and an ID that
    /// is stable for this app on this phone. The mDNS announcement needs the
    /// maker and the serial number.
    fun identity(context: Context): Array<String> {
        val resolver = context.contentResolver
        val name = Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        val id = Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID) ?: ""
        return arrayOf(name, Build.MANUFACTURER, Build.MODEL, id)
    }

    /// The phone's IPv4 addresses on Wi-Fi and other local networks. Mobile
    /// data interfaces are left out, because nothing on the local network can
    /// reach them.
    fun localAddresses(): List<String> {
        val mobile = listOf("rmnet", "ccmni", "v4-rmnet", "dummy", "tun")
        return NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && mobile.none { prefix -> it.name.startsWith(prefix) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
    }

    fun connectionStrings(): List<String> =
        localAddresses().map { if (NativeServer.PORT == 7420) "daq.nd://$it" else "daq.nd://$it:${NativeServer.PORT}" }
}
