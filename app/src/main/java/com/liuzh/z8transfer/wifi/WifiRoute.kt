package com.liuzh.z8transfer.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.liuzh.z8transfer.core.WifiEndpoint
import com.liuzh.z8transfer.core.sameIpv4Subnet
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Bind each camera socket to Wi-Fi, even when Android prefers cellular for Internet. */
internal class WifiRoute private constructor(
    private val manager: ConnectivityManager,
    private val network: Network,
    val endpoint: WifiEndpoint,
) : Closeable {
    private val live = AtomicBoolean(true)
    @Volatile var onLost: (() -> Unit)? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(lost: Network) {
            if (lost == network && live.compareAndSet(true, false)) onLost?.invoke()
        }
    }
    private var registered = false
    val available: Boolean get() = live.get()

    private fun watch() {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        manager.registerNetworkCallback(request, callback)
        registered = true
        check(available && manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            "Wi-Fi 已断开，请在系统 Wi-Fi 设置中重新连接相机"
        }
    }

    fun socket(socket: Socket = Socket()): Socket {
        try {
            check(available) { "相机 Wi-Fi 已断开" }
            socket.soTimeout = 30_000
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.receiveBufferSize = 4 * 1024 * 1024
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(InetAddress.getByName(endpoint.host), endpoint.port), 10_000)
            check(available) { "相机 Wi-Fi 已断开" }
            return socket
        } catch (error: Exception) { runCatching { socket.close() }; throw error }
    }

    override fun close() {
        live.set(false); onLost = null
        if (registered) { registered = false; runCatching { manager.unregisterNetworkCallback(callback) } }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun acquire(context: Context, requested: WifiEndpoint): WifiRoute {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val networks = manager.allNetworks.filter {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }.sortedByDescending { it == manager.activeNetwork }
            check(networks.isNotEmpty()) { "尚未连接 Wi-Fi。请先在系统 Wi-Fi 设置中连接 Z8 显示的热点，并选择保持连接" }
            for (network in networks) {
                val properties = manager.getLinkProperties(network) ?: continue
                val address = if (requested.host.isNotBlank()) requested.host else
                    properties.routes.firstNotNullOfOrNull { route ->
                        (route.gateway as? Inet4Address)?.hostAddress?.takeIf { WifiEndpoint.isLocalIpv4(it) }
                    } ?: "192.168.1.1"
                val target = InetAddress.getByName(address)
                // Require a directly attached subnet; a router default route is insufficient.
                if (properties.linkAddresses.none { link ->
                    link.address is Inet4Address && sameIpv4Subnet(link.address.address, target.address, link.prefixLength)
                }) continue
                val route = WifiRoute(manager, network, WifiEndpoint(address, requested.port))
                try { route.watch(); return route } catch (error: Exception) { route.close(); throw error }
            }
            error("当前 Wi-Fi 无法直达相机地址。请连接 Z8 热点；若地址不同，可在高级设置中填写相机 IPv4")
        }
    }
}
