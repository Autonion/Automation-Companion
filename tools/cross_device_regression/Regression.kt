import android.content.Context
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.autonion.automationcompanion.features.cross_device_automation.networking.NetworkingManager
import com.autonion.automationcompanion.features.cross_device_automation.data.*
import com.autonion.automationcompanion.features.cross_device_automation.domain.*
import com.autonion.automationcompanion.features.cross_device_automation.event_pipeline.EventReceiver
import com.autonion.automationcompanion.features.flow_automation.data.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*

suspend fun until(predicate: () -> Boolean) = withTimeout(3000) { while (!predicate()) delay(5) }
fun passed(name: String) = println("PASS | $name")
class Fixture {
    val context = Context()
    val repo = InMemoryDeviceRepository()
    val factory = OkHttpClient()
    val manager = NetworkingManager(context, repo, object : EventReceiver {
        override suspend fun onEventReceived(event: RawEvent) {}
    }, DeviceAuthManager(context), factory, 40L)
    val device = Device(id="desktop", name="Desktop", ipAddress="192.0.2.1", port=4545, status=DeviceStatus.ONLINE, agentId="agent-a")
    val sockets get() = factory.sockets.toList()
    suspend fun start() { repo.addOrUpdateDevice(device); manager.start(); until { sockets.size == 1 } }
    suspend fun open(ws: WebSocket = sockets.last()) { ws.open(); until { ws.sent.isNotEmpty() } }
    suspend fun authenticate(ws: WebSocket = sockets.last()) {
        ws.message("""{"type":"auth_result","status":"authenticated","agent_id":"agent-a","version":"2.0.5"}""")
        until { manager.hasActiveConnections() }
    }
    suspend fun stop() { manager.stop(); until { sockets.all { it.cancelled } } }
}

fun main() = runBlocking<Unit> {
    run {
        val repo = InMemoryDeviceRepository()
        val agent = Device(id="desktop", agentId="agent-a", name="Desktop", ipAddress="192.0.2.1", port=4545)
        repo.addOrUpdateDevice(agent)
        repo.mutateDevice(agent.id) { it.copy(connectionState=ConnectionState.CONNECTED, isServiceOnly=false) }
        repo.addOrUpdateDevice(agent.copy(isServiceOnly=true))
        check(!repo.getDeviceById(agent.id)!!.isServiceOnly)
        repo.mutateDevice(agent.id) { it.copy(isServiceOnly=true) }
        repo.addOrUpdateDevice(agent.copy(isServiceOnly=false))
        check(repo.getDeviceById(agent.id)!!.isServiceOnly)
        repo.mutateDevice(agent.id) { it.copy(connectionState=ConnectionState.DISCONNECTED) }
        repo.addOrUpdateDevice(agent.copy(isServiceOnly=false))
        check(!repo.getDeviceById(agent.id)!!.isServiceOnly)
        passed("authenticated connection mode survives stale helper or app discovery results")
    }
    Fixture().apply {
        context.getSharedPreferences("autonion_secure_auth_prefs", 0).edit()
            .putString("device_id", "synthetic-phone")
            .putString("agent_secret_agent-a", "A".repeat(43) + "=").apply()
        start(); open()
        val wire = sockets.single().sent.first()
        check(wire.contains("\\u003d"))
        java.io.File("build/cross-device-regression/gson-client-info.json").writeText(wire)
        stop(); passed("production Android identity encoder generates the native helper wire fixture")
    }
    run {
        val context = Context()
        val auth = DeviceAuthManager(context)
        val saved = Device(id="saved", agentId="trusted", name="Saved", ipAddress="192.0.2.9", port=4545,
            isSelected=true, isPaired=true, connectionState=ConnectionState.CONNECTED)
        auth.rememberDevice(saved)
        check(auth.rememberedDevices().isEmpty())
        auth.markAgentPaired("trusted"); auth.rememberDevice(saved)
        check(!DeviceAuthManager(context).rememberedDevices().single().isConnected)
        val factory = OkHttpClient()
        val repo = InMemoryDeviceRepository()
        val manager = NetworkingManager(context, repo, object : EventReceiver {
            override suspend fun onEventReceived(event: RawEvent) {}
        }, DeviceAuthManager(context), factory, 5_000)
        manager.start(); until { factory.sockets.size == 1 }
        check(factory.sockets.single().request.url.text.contains("192.0.2.9"))
        factory.sockets.single().fail(); until { factory.sockets.single().cancelled }
        manager.retrySelectedConnections(); until { factory.sockets.size == 2 }
        check(!manager.hasActiveConnections()) // Cached trust never substitutes for authentication.
        manager.stop(); until { factory.sockets.all { it.cancelled } }
        auth.rememberDevice(saved.copy(isSelected=false))
        val stoppedRepo = InMemoryDeviceRepository()
        val stoppedFactory = OkHttpClient()
        val stoppedManager = NetworkingManager(context, stoppedRepo, object : EventReceiver {
            override suspend fun onEventReceived(event: RawEvent) {}
        }, DeviceAuthManager(context), stoppedFactory)
        stoppedManager.start(); until { runBlocking { stoppedRepo.getDeviceById("saved") != null } }; delay(50)
        check(stoppedFactory.sockets.isEmpty())
        stoppedManager.stop(); auth.unpairAgent("trusted")
        check(auth.rememberedDevices().isEmpty())
        passed("paired endpoints restore without discovery; deselection and revocation are preserved")
    }
    Fixture().apply {
        start(); open(); authenticate()
        manager.retrySelectedConnections(); delay(80)
        check(sockets.size == 1 && manager.hasActiveConnections())
        sockets.last().message("""{"type":"auth_result","status":"pairing_revoked"}""")
        until { sockets.last().cancelled }
        manager.retrySelectedConnections(); delay(80)
        check(sockets.size == 1)
        stop(); passed("network recovery preserves healthy connections and never bypasses revocation")
    }
    run {
        val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val context = Context()
        val repo = InMemoryDeviceRepository()
        var recoveries = 0
        val host = com.autonion.automationcompanion.features.cross_device_automation.host_management.HostManager(
            context, repo, onNetworkReady={ recoveries++ }, dispatcher=dispatcher, refreshIntervalMs=60_000)
        host.startDiscovery(); until { context.nsd.scans.size == 1 && context.connectivity.callback != null }
        val old = context.nsd.scans.single()
        context.connectivity.callback!!.onAvailable(android.net.Network())
        until { context.nsd.scans.size == 2 && recoveries == 1 }
        old.onServiceFound(android.net.nsd.NsdServiceInfo()); delay(30)
        check(repo.getAllDevices().first().isEmpty())
        context.nsd.scans.last().onServiceFound(android.net.nsd.NsdServiceInfo())
        until { runBlocking { repo.getAllDevices().first().isNotEmpty() } }
        host.stopDiscovery(); until { context.connectivity.callback == null }
        dispatcher.close()
        passed("network return refreshes NSD; callbacks from the old scan are ignored")
    }
    run {
        val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val context = Context()
        val host = com.autonion.automationcompanion.features.cross_device_automation.host_management.HostManager(
            context, InMemoryDeviceRepository(), dispatcher=dispatcher, refreshIntervalMs=100)
        host.startDiscovery(); until { context.nsd.scans.size >= 2 }
        host.stopDiscovery(); until { context.connectivity.callback == null }
        val scans = context.nsd.scans.size; delay(150)
        check(context.nsd.scans.size == scans)
        dispatcher.close(); passed("a silent empty discovery scan restarts without toggling sync")
    }
    Fixture().apply {
        manager.listener = object : NetworkingManager.NetworkingListener {
            override fun onDeviceConnected(device: Device) { manager.broadcast(mapOf("type" to "list_flows")) }
            override fun onDeviceDisconnected(deviceId: String) {}
            override fun onMessageReceived(deviceId: String, rawJson: String) {}
        }
        start(); open()
        check(!manager.hasActiveConnections() && sockets.last().sent.size == 1)
        check(sockets.last().sent.first().contains("client_info"))
        authenticate(); until { sockets.last().sent.size == 2 }
        check(sockets.last().sent.last().contains("list_flows"))
        check(repo.getDeviceById(device.id)!!.isConnected)
        stop(); passed("sync waits for authentication")
    }
    Fixture().apply {
        start(); open(); authenticate(); val old = sockets.last()
        old.closing(); old.closed()
        until { sockets.size == 2 }
        check(!manager.hasActiveConnections())
        open(); authenticate(); old.closed(); old.fail(); delay(80)
        check(manager.hasActiveConnections() && sockets.size == 2)
        stop(); passed("graceful close retries; obsolete callbacks preserve replacement")
    }
    Fixture().apply {
        start(); val pending = sockets.last()
        repo.deselectAllDevices(); pending.open()
        until { pending.cancelled }; delay(80)
        check(!manager.hasActiveConnections() && pending.sent.isEmpty())
        stop(); passed("deselection cancels an in-flight handshake")
    }
    Fixture().apply {
        start(); open(); authenticate(); val previous = sockets.last()
        previous.closing(1005)
        check(previous.closeRequested)
        previous.closed(1005)
        until { sockets.size == 2 }; open(); authenticate()
        check(manager.hasActiveConnections())
        stop(); passed("empty close frames are acknowledged legally and reconnect")
    }
    Fixture().apply {
        start(); open(); authenticate()
        repo.mutateDevice(device.id) { it.copy(ipAddress="192.0.2.2", port=5656) }
        until { sockets.size == 2 }; open(); authenticate()
        repo.mutateDevice(device.id) { it.copy(ipAddress="192.0.2.1", port=4545) }
        until { sockets.size == 3 }; open(); authenticate()
        check(manager.hasActiveConnections())
        stop(); passed("IP/port changes release the original endpoint")
    }
    Fixture().apply {
        start(); sockets.last().fail(); sockets.last().fail()
        until { sockets.size == 2 }; sockets.last().fail(); sockets.last().fail()
        until { sockets.size == 3 }; sockets.last().fail(); sockets.last().fail()
        until { sockets.size == 4 }; delay(160)
        check(sockets.size == 4) // The fourth attempt is pending, so no parallel retry may open another socket.
        stop(); passed("failures keep one retry per device")
    }
    Fixture().apply {
        start(); open(); authenticate(); stop()
        check(repo.getDeviceById(device.id)!!.isSelected)
        manager.start(); until { sockets.size == 2 }; open(); authenticate()
        check(manager.hasActiveConnections()); stop(); passed("stop/start restores selected peers")
    }
    Fixture().apply {
        start(); open()
        sockets.last().message("""{"type":"connection_ack","status":"connected","prelogin":true}""")
        until { sockets.last().cancelled }; delay(100)
        check(!manager.hasActiveConnections() && !repo.getDeviceById(device.id)!!.isPaired)
        stop(); passed("legacy unauthenticated helper acknowledgment is rejected")
    }
    Fixture().apply {
        start(); open(); sockets.last().message("""{"type":"auth_result","status":"pairing_required","agent_id":"agent-a"}""")
        until { runBlocking { repo.getDeviceById(device.id)!!.connectionState == ConnectionState.PAIRING } }
        manager.retrySelectedConnections(); manager.retrySelectedConnections(device.id); delay(80)
        check(sockets.size == 1 && !sockets.last().cancelled)
        manager.submitPairingPin(device.id,"123456")
        until { sockets.last().sent.any { it.contains("pairing_submit") } }
        check(!manager.hasActiveConnections())
        sockets.last().message("""{"type":"auth_result","status":"paired_success","agent_id":"agent-a"}""")
        until { manager.hasActiveConnections() }; stop(); passed("PIN uses the pending socket without marking it ready")
    }
    Fixture().apply {
        repo.addOrUpdateDevice(device.copy(ipAddress="",id="invalid"))
        repo.addOrUpdateDevice(device.copy(isSelected=true))
        manager.start(); until { sockets.size == 1 }; open(); authenticate()
        check(manager.hasActiveConnections()); stop(); passed("invalid endpoints do not kill other devices")
    }
    Fixture().apply {
        factory.failNextCreation = true
        start(); open(); authenticate()
        check(manager.hasActiveConnections())
        stop(); passed("socket creation exceptions release the attempt and retry")
    }
    run {
        val context = Context()
        val prefs = context.getSharedPreferences("autonion_secure_auth_prefs", 0)
        val auth = DeviceAuthManager(context)
        val legacy = auth.deviceSecret
        prefs.edit().putStringSet("paired_agent_ids", setOf("a","b")).apply()
        check(auth.secretForAgent("a") == legacy && auth.secretForAgent("b") == legacy)
        auth.unpairAgent("a")
        check(auth.secretForAgent("a") != legacy && auth.secretForAgent("b") == legacy)
        check(auth.secretForAgent("new-a") != auth.secretForAgent("new-b"))
        passed("per-agent credentials preserve legacy pairings and isolate revocation")
    }
    Fixture().apply {
        val flows = DesktopFlowManager(context, CrossDeviceAutomationManager(manager))
        manager.listener = object : NetworkingManager.NetworkingListener {
            override fun onDeviceConnected(device: Device) { flows.requestFlowList(device.id) }
            override fun onDeviceDisconnected(deviceId: String) { flows.onDeviceDisconnected(deviceId) }
            override fun onMessageReceived(deviceId: String, rawJson: String) { flows.handleIncomingMessage(rawJson, deviceId) }
        }
        start(); open()
        sockets.last().message("""{"type":"auth_result","status":"authenticated","agent":"autonion-prelogin","prelogin":true,"agent_id":"agent-a","version":"2.0.5"}""")
        until { sockets.last().sent.any { it.contains("list_flows") } }
        check(repo.getDeviceById(device.id)!!.let { it.isConnected && it.isServiceOnly })
        val request = Gson().fromJson(sockets.last().sent.last(), Map::class.java)
        sockets.last().message("""{"type":"flow_list_response","transactionId":"${request["transactionId"]}","flows":[{"id":"unlock","name":"Unlock","nodeCount":1,"version":1}],"prelogin":true}""")
        until { flows.desktopFlows.value.size == 1 }
        check(!flows.isLoading.value)
        stop(); passed("authenticated pre-login helper loads unlock flows without the desktop Agent")
    }
    Fixture().apply {
        start(); open(); authenticate()
        val flows = DesktopFlowManager(context, CrossDeviceAutomationManager(manager))
        flows.requestFlowList(device.id)
        val gson = Gson()
        val listRequest = gson.fromJson(sockets.last().sent.last(), Map::class.java)
        flows.handleIncomingMessage("""{"type":"flow_list_response","transactionId":"${listRequest["transactionId"]}","flows":[{"id":"flow-a","name":"Test"}]}""",device.id)
        check(!flows.isLoading.value && flows.desktopFlows.value.single().deviceId == device.id)
        flows.triggerFlow("flow-a")
        val request = gson.fromJson(sockets.last().sent.last(), Map::class.java)
        flows.handleIncomingMessage("""{"type":"flow_trigger_response","transactionId":"${request["transactionId"]}","flowId":"flow-a","status":"completed"}""", "another-peer")
        check(flows.runningFlowId.value == "flow-a")
        flows.onDeviceDisconnected("unrelated")
        check(flows.runningFlowId.value == "flow-a")
        flows.handleIncomingMessage("""{"type":"flow_trigger_response","transactionId":"obsolete","flowId":"flow-a","status":"completed"}""",device.id)
        check(flows.runningFlowId.value == "flow-a")
        val result = async(start=CoroutineStart.UNDISPATCHED) { flows.progressUpdates.first() }
        flows.onDeviceDisconnected(device.id)
        check(withTimeout(1000) { result.await() }.status == FlowTriggerStatus.FAILED)
        check(flows.runningFlowId.value == null)
        flows.handleIncomingMessage("""{"type":"flow_trigger_response","transactionId":"${request["transactionId"]}","flowId":"flow-a","status":"completed"}""",device.id)
        check(flows.runningFlowId.value == null)
        stop(); passed("flow ownership and transaction matching; disconnect never claims completion")
    }
}
