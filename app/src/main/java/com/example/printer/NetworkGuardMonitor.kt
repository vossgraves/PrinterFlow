package com.example.printer

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

enum class NetworkGuardMode {
    WIFI_BOUND,
    WIFI_AND_MOBILE_GUARDED,
    MOBILE_DATA_FALLBACK,
    OFFLINE
}

data class NetworkGuardState(
    val mode: NetworkGuardMode = NetworkGuardMode.OFFLINE,
    val hasWifi: Boolean = false,
    val hasCellular: Boolean = false,
    val wifiHasInternet: Boolean = false,
    val wifiLocalIp: String? = null,
    val wifiGatewayHint: String? = null,
    val isLikelyWifiDirect: Boolean = false
) {
    val canReachLanPrinter: Boolean
        get() = hasWifi
}

class NetworkGuardMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var activeWifiNetwork: Network? = null

    @Volatile
    private var activeCellularNetwork: Network? = null

    private val _state = MutableStateFlow(NetworkGuardState())
    val state: StateFlow<NetworkGuardState> = _state.asStateFlow()

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            activeWifiNetwork = network
            refreshState()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            activeWifiNetwork = network
            refreshState()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            activeWifiNetwork = network
            refreshState()
        }

        override fun onLost(network: Network) {
            if (activeWifiNetwork == network) {
                activeWifiNetwork = null
            }
            refreshState()
        }
    }

    private val cellularCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            activeCellularNetwork = network
            refreshState()
        }

        override fun onLost(network: Network) {
            if (activeCellularNetwork == network) {
                activeCellularNetwork = null
            }
            refreshState()
        }
    }

    private var registered = false

    fun start() {
        if (registered) return
        registered = true
        // Observe Wi-Fi WITHOUT requiring NET_CAPABILITY_INTERNET so HP Wi-Fi Direct networks are captured!
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cellularRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        runCatching { connectivityManager.registerNetworkCallback(wifiRequest, wifiCallback) }
        runCatching { connectivityManager.registerNetworkCallback(cellularRequest, cellularCallback) }
        seedCurrentNetworks()
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivityManager.unregisterNetworkCallback(wifiCallback) }
        runCatching { connectivityManager.unregisterNetworkCallback(cellularCallback) }
    }

    private fun seedCurrentNetworks() {
        runCatching {
            @Suppress("DEPRECATION")
            val allNetworks = connectivityManager.allNetworks
            for (net in allNetworks) {
                val caps = connectivityManager.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    activeWifiNetwork = net
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    activeCellularNetwork = net
                }
            }
        }
        refreshState()
    }

    fun refreshState() {
        val wifiNet = activeWifiNetwork
        val cellNet = activeCellularNetwork

        val wifiCaps = wifiNet?.let { runCatching { connectivityManager.getNetworkCapabilities(it) }.getOrNull() }
        val linkProps = wifiNet?.let { runCatching { connectivityManager.getLinkProperties(it) }.getOrNull() }

        val hasWifi = wifiNet != null && wifiCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val hasCellular = cellNet != null
        val wifiHasInternet = wifiCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        val ipv4 = linkProps?.linkAddresses
            ?.mapNotNull { it.address as? Inet4Address }
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress

        val gateway = linkProps?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway
            ?.hostAddress

        val isLikelyWifiDirect = ipv4?.startsWith("192.168.223.") == true ||
            gateway == "192.168.223.1"

        val mode = when {
            hasWifi && hasCellular -> NetworkGuardMode.WIFI_AND_MOBILE_GUARDED
            hasWifi -> NetworkGuardMode.WIFI_BOUND
            hasCellular -> NetworkGuardMode.MOBILE_DATA_FALLBACK
            else -> NetworkGuardMode.OFFLINE
        }

        _state.value = NetworkGuardState(
            mode = mode,
            hasWifi = hasWifi,
            hasCellular = hasCellular,
            wifiHasInternet = wifiHasInternet,
            wifiLocalIp = ipv4,
            wifiGatewayHint = gateway,
            isLikelyWifiDirect = isLikelyWifiDirect
        )
    }

    fun getBoundSocketFactory(forceWifiBinding: Boolean): SocketFactory {
        val wifi = activeWifiNetwork
        return if (forceWifiBinding && wifi != null) {
            wifi.socketFactory
        } else {
            SocketFactory.getDefault()
        }
    }

    fun createBoundHttpClient(
        forceWifiBinding: Boolean,
        connectTimeoutSec: Long = 5,
        readTimeoutSec: Long = 20,
        writeTimeoutSec: Long = 45
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)

        val wifi = activeWifiNetwork
        if (forceWifiBinding && wifi != null) {
            builder.socketFactory(wifi.socketFactory)
        }
        return builder.build()
    }
}
