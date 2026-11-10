package com.liuzh.z8transfer.wifi

import android.content.Context
import android.net.*
import androidx.test.core.app.ApplicationProvider
import com.liuzh.z8transfer.core.WifiEndpoint
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Suppress("DEPRECATION")
class WifiRouteTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val shadow = shadowOf(manager)
    private val wifi = ShadowNetwork.newInstance(108)

    @Before fun setup() { shadow.clearAllNetworks() }
    private fun addWifi(gateway: Boolean = true) {
        shadow.addNetwork(wifi, ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED))
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadow.setNetworkCapabilities(wifi, caps)
        val local = ReflectionHelpers.callConstructor(LinkAddress::class.java,
            ClassParameter.from(InetAddress::class.java, InetAddress.getByName("192.168.1.20")),
            ClassParameter.from(Int::class.javaPrimitiveType!!, 24))
        val properties = LinkProperties().apply {
            interfaceName = "wlan0"
            setLinkAddresses(listOf(local))
            if (gateway) addRoute(ReflectionHelpers.callConstructor(RouteInfo::class.java,
                ClassParameter.from(IpPrefix::class.java, IpPrefix(InetAddress.getByName("0.0.0.0"), 0)),
                ClassParameter.from(InetAddress::class.java, InetAddress.getByName("192.168.1.1")),
                ClassParameter.from(String::class.java, "wlan0")))
        }
        shadow.setLinkProperties(wifi, properties)
    }

    @Test fun unvalidatedCameraWifiWorksAndSocketIsBoundBeforeConnecting() {
        addWifi()
        val mobile = ShadowNetwork.newInstance(109)
        shadow.addNetwork(mobile, ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_MOBILE, 0, true, NetworkInfo.State.CONNECTED))
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadow.setNetworkCapabilities(mobile, caps)
        val bound = shadowOf(wifi)
        var connected = false
        val fake = object : Socket() {
            override fun connect(address: SocketAddress, timeout: Int) {
                assertTrue(bound.isSocketBound(this))
                assertEquals("192.168.1.1", (address as InetSocketAddress).address.hostAddress)
                assertEquals(15740, address.port); assertEquals(10_000, timeout)
                connected = true
            }
        }
        WifiRoute.acquire(context, WifiEndpoint()).use { route ->
            assertEquals(WifiEndpoint("192.168.1.1"), route.endpoint)
            route.socket(fake).close()
            assertTrue(connected); assertEquals(1, bound.boundSocketCount())
            assertEquals(0, shadowOf(mobile).boundSocketCount())
        }
        assertTrue(shadow.networkCallbacks.isEmpty())
    }

    @Test fun networkLossNotifiesOnceAndPreventsReusingOldRoute() {
        addWifi()
        WifiRoute.acquire(context, WifiEndpoint()).use { route ->
            var lost = 0; route.onLost = { lost++ }
            val callback = shadow.networkCallbacks.single()
            callback.onLost(ShadowNetwork.newInstance(999))
            assertTrue(route.available); assertEquals(0, lost)
            callback.onLost(wifi); callback.onLost(wifi)
            assertFalse(route.available); assertEquals(1, lost)
            val socket = object : Socket() { var closed = false; override fun close() { closed = true } }
            assertThrows(IllegalStateException::class.java) { route.socket(socket) }
            assertTrue(socket.closed)
        }
        assertTrue(shadow.networkCallbacks.isEmpty())
    }

    @Test fun cellularAloneAndOffSubnetManualAddressAreRejected() {
        val error = assertThrows(IllegalStateException::class.java) { WifiRoute.acquire(context, WifiEndpoint()) }
        assertTrue(error.message!!.contains("Wi-Fi"))
        addWifi()
        assertThrows(IllegalStateException::class.java) { WifiRoute.acquire(context, WifiEndpoint("10.10.0.1")) }
        assertTrue(shadow.networkCallbacks.isEmpty())
    }

    @Test fun explicitAddressDoesNotUseRouterGatewayAndNoGatewayCanUseDefaultCameraAddress() {
        addWifi(gateway = false)
        WifiRoute.acquire(context, WifiEndpoint()).use { assertEquals("192.168.1.1", it.endpoint.host) }
        WifiRoute.acquire(context, WifiEndpoint("192.168.1.8", 15741)).use { assertEquals(WifiEndpoint("192.168.1.8", 15741), it.endpoint) }
    }
}
