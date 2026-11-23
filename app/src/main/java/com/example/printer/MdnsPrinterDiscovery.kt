package com.example.printer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets

data class DiscoveredPrinter(
    val serviceName: String,
    val host: String,
    val port: Int,
    val ippPath: String,
    val model: String
)

/**
 * Real Android mDNS/DNS-SD discovery scanning _ipp._tcp. services on the local Wi-Fi network.
 */
class MdnsPrinterDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredPrinter>>(emptyList())
    val discovered: StateFlow<List<DiscoveredPrinter>> = _discovered.asStateFlow()

    fun startScan(onLog: (String) -> Unit) {
        if (_isScanning.value) return
        _discovered.value = emptyList()

        runCatching {
            multicastLock = wifiManager?.createMulticastLock("inkflow_mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                _isScanning.value = true
                onLog("mDNS scan started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                onLog("mDNS candidate: ${service.serviceName}")
                resolveServiceCompat(service, onLog)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                _discovered.value = _discovered.value.filterNot { it.serviceName == service.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                _isScanning.value = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _isScanning.value = false
                onLog("mDNS start failed (code $errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                _isScanning.value = false
            }
        }
        discoveryListener = listener
        runCatching {
            nsdManager.discoverServices("_ipp._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            _isScanning.value = false
            onLog("mDNS discovery unavailable: ${it.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveServiceCompat(service: NsdServiceInfo, onLog: (String) -> Unit) {
        runCatching {
            nsdManager.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        onLog("mDNS resolve failed for ${serviceInfo.serviceName} ($errorCode)")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val hostAddr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            info.hostAddresses.firstOrNull()?.hostAddress ?: info.host?.hostAddress
                        } else {
                            info.host?.hostAddress
                        } ?: return

                        val rp = info.attributes["rp"]?.let { String(it, StandardCharsets.UTF_8) }
                            ?.takeIf { it.isNotBlank() }
                            ?: "ipp/print"
                        val path = if (rp.startsWith("/")) rp else "/$rp"
                        val ty = info.attributes["ty"]?.let { String(it, StandardCharsets.UTF_8) }
                            ?: info.serviceName

                        val item = DiscoveredPrinter(
                            serviceName = info.serviceName,
                            host = hostAddr,
                            port = info.port.takeIf { it > 0 } ?: 631,
                            ippPath = path,
                            model = ty
                        )
                        val current = _discovered.value.filterNot { it.host == item.host && it.port == item.port }
                        _discovered.value = current + item
                        onLog("Discovered IPP printer: ${item.model} at ${item.host}:${item.port}${item.ippPath}")
                    }
                }
            )
        }
    }

    fun stopScan() {
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
        _isScanning.value = false
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        multicastLock = null
    }
}
