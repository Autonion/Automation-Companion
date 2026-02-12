package com.autonion.automationcompanion.features.cross_device_automation.host_management

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.autonion.automationcompanion.features.cross_device_automation.domain.Device
import com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceRepository
import com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.UUID

class HostManager(
    private val context: Context,
    private val deviceRepository: DeviceRepository
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_myautomation._tcp" // Removing .local as Android adds it automatically often, but standard is _type._tcp.
    private val discoveryTag = "HostManager"

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(discoveryTag, "Service discovery started")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d(discoveryTag, "Service discovery success: $service")
            if (service.serviceType.contains("_myautomation")) {
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(discoveryTag, "Resolve failed: $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        Log.d(discoveryTag, "Resolve Succeeded. $serviceInfo")
                        
                        // Treat as a new device found
                        val host = serviceInfo.host
                        val port = serviceInfo.port
                        val serviceName = serviceInfo.serviceName

                        // In a real app, we'd handshake here. For now, we auto-add.
                        // Assuming serviceName contains some unique ID or we generate one.
                        // Ideally the agent advertises its UUID in TXT records, but NsdServiceInfo parsing varies.
                        
                        CoroutineScope(Dispatchers.IO).launch {
                            val device = Device(
                                id = UUID.nameUUIDFromBytes(serviceName.toByteArray()).toString(), // Deterministic UUID from name for now
                                name = serviceName,
                                ipAddress = host.hostAddress ?: "",
                                port = port,
                                status = DeviceStatus.ONLINE
                            )
                            deviceRepository.addOrUpdateDevice(device)
                        }
                    }
                })
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.e(discoveryTag, "service lost: $service")
            // Mark as offline
             CoroutineScope(Dispatchers.IO).launch {
                  val id = UUID.nameUUIDFromBytes(service.serviceName.toByteArray()).toString()
                  val existing = deviceRepository.getDeviceById(id)
                  if (existing != null) {
                      deviceRepository.addOrUpdateDevice(existing.copy(status = DeviceStatus.OFFLINE))
                  }
             }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(discoveryTag, "Discovery stopped: $serviceType")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(discoveryTag, "Discovery failed: Error code:$errorCode")
            nsdManager.stopServiceDiscovery(this)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(discoveryTag, "Discovery failed: Error code:$errorCode")
            nsdManager.stopServiceDiscovery(this)
        }
    }

    fun startDiscovery() {
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(discoveryTag, "Failed to start discovery", e)
        }
    }

    fun stopDiscovery() {
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.e(discoveryTag, "Failed to stop discovery", e)
        }
    }
}
