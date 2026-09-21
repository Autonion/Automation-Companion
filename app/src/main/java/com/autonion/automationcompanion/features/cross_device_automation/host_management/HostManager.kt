package com.autonion.automationcompanion.features.cross_device_automation.host_management

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.autonion.automationcompanion.features.cross_device_automation.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.Inet4Address
import java.util.UUID
import kotlin.coroutines.resume

/** NSD callbacks and the resolve queue are serialized on Main. Every scan has a generation. */
class HostManager(context: Context, private val deviceRepository: DeviceRepository,
    private val onNetworkReady: () -> Unit = {},
    private val onDeviceResolved: (String) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val refreshIntervalMs: Long = 15_000L) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val serviceType = "_myautomation._tcp"
    private var enabled = false
    private var generation = 0L
    private var discovery: NsdManager.DiscoveryListener? = null
    private var discoveryRetry: Job? = null
    private var discoveryFailures = 0
    private var resolver: Job? = null
    private var refresh: Job? = null
    private val services = linkedMapOf<String, NsdServiceInfo>()
    private val pending = linkedMapOf<String, NsdServiceInfo>()
    private val deviceIds = mutableMapOf<String, String>()
    private val resolveRetries = mutableMapOf<String, Job>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkRefresh: Job? = null

    fun startDiscovery() { scope.launch {
        if (enabled) return@launch
        enabled = true
        val epoch = ++generation
        discover(epoch)
        watchNetworks()
        refresh = scope.launch {
            while (enabled) {
                delay(refreshIntervalMs)
                // A silent scan with no results cannot recover by resolving an empty cache.
                if (services.isEmpty()) restartScan()
                else services.values.toList().forEach { enqueue(it, generation) }
            }
        }
    } }

    fun stopDiscovery() { scope.launch {
        enabled = false; generation++
        networkRefresh?.cancel(); networkRefresh = null
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
        discoveryRetry?.cancel(); discoveryRetry = null
        refresh?.cancel(); refresh = null
        resolver?.cancel(); resolver = null
        resolveRetries.values.forEach { it.cancel() }; resolveRetries.clear()
        val previous = discovery; discovery = null
        services.clear(); pending.clear(); deviceIds.clear()
        if (previous != null) runCatching { nsdManager.stopServiceDiscovery(previous) }
    } }

    private fun current(epoch: Long) = enabled && epoch == generation

    private fun watchNetworks() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            private val identity: ConnectivityManager.NetworkCallback get() = this
            private fun changed() { scope.launch {
                if (!enabled || networkCallback !== identity) return@launch
                networkRefresh?.cancel()
                networkRefresh = scope.launch {
                    delay(250)
                    if (enabled && networkCallback === identity) {
                        restartScan()
                        onNetworkReady()
                    }
                }
            } }
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
        }
        networkCallback = callback
        try {
            // Local Wi-Fi can serve a desktop even when Android cannot validate Internet access.
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
            connectivity.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.w("HostManager", "Network monitoring unavailable; periodic discovery remains active", e)
            networkCallback = null
        }
    }

    private fun restartScan() {
        if (!enabled) return
        val previous = discovery
        discovery = null
        val epoch = ++generation
        discoveryRetry?.cancel(); discoveryRetry = null
        resolver?.cancel(); resolver = null
        resolveRetries.values.forEach { it.cancel() }; resolveRetries.clear()
        services.clear(); pending.clear(); deviceIds.clear()
        if (previous != null) runCatching { nsdManager.stopServiceDiscovery(previous) }
        Log.d("HostManager", "Refreshing discovery for current network")
        discover(epoch)
    }

    private fun discover(epoch: Long) {
        if (!current(epoch) || discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            private val identity: NsdManager.DiscoveryListener get() = this
            private fun event(block: suspend () -> Unit) { scope.launch {
                if (current(epoch) && discovery === identity) block()
            } }
            override fun onDiscoveryStarted(type: String) = event { discoveryFailures = 0 }
            override fun onServiceFound(service: NsdServiceInfo) = event {
                if (service.serviceType.contains("_myautomation")) {
                    services[service.serviceName] = service
                    enqueue(service, epoch)
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) = event {
                val name = service.serviceName
                services.remove(name); pending.remove(name); resolveRetries.remove(name)?.cancel()
                deviceIds.remove(name)?.let { id -> deviceRepository.mutateDevice(id) {
                    it.copy(status = DeviceStatus.OFFLINE)
                } }
            }
            override fun onDiscoveryStopped(type: String) = event { failed(epoch, identity) }
            override fun onStartDiscoveryFailed(type: String, code: Int) = event { failed(epoch, identity) }
            override fun onStopDiscoveryFailed(type: String, code: Int) = event { failed(epoch, identity) }
        }
        discovery = listener
        try { nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (e: Exception) { Log.w("HostManager", "Discovery start failed", e); failed(epoch, listener) }
    }

    private fun failed(epoch: Long, listener: NsdManager.DiscoveryListener) {
        if (!current(epoch) || discovery !== listener) return
        discovery = null
        runCatching { nsdManager.stopServiceDiscovery(listener) }
        if (discoveryRetry?.isActive == true) return
        val wait = (2_000L shl discoveryFailures.coerceAtMost(4)).coerceAtMost(30_000L)
        discoveryFailures++
        discoveryRetry = scope.launch {
            delay(wait)
            discoveryRetry = null
            discover(epoch)
        }
    }

    private fun enqueue(service: NsdServiceInfo, epoch: Long) {
        if (!current(epoch)) return
        pending[service.serviceName] = service
        if (resolver?.isActive == true) return
        resolver = scope.launch {
            while (current(epoch) && pending.isNotEmpty()) {
                val (name, found) = pending.entries.first().let { it.key to it.value }
                pending.remove(name)
                val resolved = withTimeoutOrNull(15_000) { resolve(found) }
                if (!current(epoch) || name !in services) continue
                if (resolved != null && publish(resolved, name)) {
                    resolveRetries.remove(name)?.cancel()
                } else if (resolveRetries[name]?.isActive != true) {
                    resolveRetries[name] = scope.launch {
                        delay(5_000)
                        resolveRetries.remove(name)
                        if (current(epoch)) services[name]?.let { enqueue(it, epoch) }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(service: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { continuation ->
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) { continuation.resume(null) }
            override fun onServiceResolved(info: NsdServiceInfo) { continuation.resume(info) }
        }
        continuation.invokeOnCancellation {
            if (Build.VERSION.SDK_INT >= 34) runCatching { nsdManager.stopServiceResolution(listener) }
        }
        try { nsdManager.resolveService(service, listener) }
        catch (e: Exception) { continuation.resume(null) }
    }

    @Suppress("DEPRECATION")
    private suspend fun publish(info: NsdServiceInfo, serviceName: String): Boolean {
        // The desktop serves IPv4. Prefer the address resolved on this network over its TXT fallback.
        val address = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
        } else (info.host as? Inet4Address)?.hostAddress
        val txt = info.attributes
        val fallback = txt["host"]?.toString(Charsets.UTF_8)?.takeIf { host ->
            val octets = host.split('.')
            octets.size == 4 && octets.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
        }
        val ipv4 = address ?: fallback ?: return false
        if (info.port !in 1..65535) return false
        val agentId = txt["device_id"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
        val agent = txt["agent"]?.toString(Charsets.UTF_8).orEmpty()
        val serviceOnly = txt["prelogin"]?.toString(Charsets.UTF_8).equals("true", true) ||
            agent.contains("prelogin", true)
        val id = agentId ?: UUID.nameUUIDFromBytes(serviceName.toByteArray()).toString()
        deviceRepository.addOrUpdateDevice(Device(id = id, name = serviceName, ipAddress = ipv4,
            port = info.port, status = DeviceStatus.ONLINE, agentId = agentId, isServiceOnly = serviceOnly))
        val stored = deviceRepository.getAllDevices().first().firstOrNull {
            it.id == id || (agentId != null && it.agentId == agentId)
        }
        deviceIds[serviceName] = stored?.id ?: id
        onDeviceResolved(stored?.id ?: id)
        return true
    }
}
