"""Deterministic JVM tests of production connection/auth/flow code with platform and socket doubles.

Run alongside the Android Gradle build, which verifies the real Android/OkHttp APIs.
No phone, local server, desktop unlock, or real credential store is accessed.
"""
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'build/cross-device-regression'
OUT.mkdir(parents=True, exist_ok=True)
BASE = ROOT / 'app/src/main/java/com/autonion/automationcompanion/features'
stubs = {
'Context.kt': '''package android.content
import java.util.concurrent.ConcurrentHashMap
class Context {
 companion object { const val MODE_PRIVATE = 0; const val NSD_SERVICE="nsd"; const val CONNECTIVITY_SERVICE="connectivity" }
 val nsd = android.net.nsd.NsdManager()
 val connectivity = android.net.ConnectivityManager()
 fun getSystemService(name: String): Any = if (name == NSD_SERVICE) nsd else connectivity
 val packageManager = PackageManager()
 val packageName = "test"
 private val stores = ConcurrentHashMap<String, SharedPreferences>()
 fun getSharedPreferences(name: String, mode: Int) = stores.computeIfAbsent(name) { SharedPreferences() }
}
class PackageManager { fun getPackageInfo(name: String, flags: Int) = PackageInfo() }
class PackageInfo { val versionName: String? = "1.1.3" }
class SharedPreferences {
 private val values = ConcurrentHashMap<String, Any>()
 fun getString(key: String, default: String?) = values[key] as? String ?: default
 fun getStringSet(key: String, default: Set<String>?) = values[key] as? Set<String> ?: default
 fun edit() = Editor()
 inner class Editor {
  private val writes = mutableMapOf<String, Any>()
  fun putString(key: String, value: String) = apply { writes[key] = value }
  fun putStringSet(key: String, value: Set<String>) = apply { writes[key] = value.toSet() }
  fun apply() { values.putAll(writes) }
 }
}
''',
'Util.kt': '''package android.util
object Log {
 fun d(vararg args: Any?) = 0
 fun i(vararg args: Any?) = 0
 fun w(vararg args: Any?) = 0
 fun e(vararg args: Any?) = 0
}
object Base64 { const val NO_WRAP = 2; fun encodeToString(bytes: ByteArray, flags: Int) = java.util.Base64.getEncoder().encodeToString(bytes) }
''',
'Build.kt': '''package android.os
object Build { const val MANUFACTURER="Google"; const val MODEL="Pixel 8"; object VERSION { const val SDK_INT=34 } }
''',
'Network.kt': '''package android.net
class Network
object NetworkCapabilities { const val TRANSPORT_WIFI=1; const val TRANSPORT_ETHERNET=3 }
class NetworkRequest { class Builder { fun addTransportType(type: Int)=this; fun build()=NetworkRequest() } }
class ConnectivityManager {
 open class NetworkCallback { open fun onAvailable(network: Network) {}; open fun onLost(network: Network) {} }
 @Volatile var callback: NetworkCallback?=null
 fun registerNetworkCallback(request: NetworkRequest, listener: NetworkCallback) { callback=listener }
 fun unregisterNetworkCallback(listener: NetworkCallback) { if (callback===listener) callback=null }
}
''',
'Nsd.kt': '''package android.net.nsd
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
class NsdServiceInfo {
 var serviceName="Desktop"; var serviceType="_myautomation._tcp"; var port=4545
 var host: InetAddress?=InetAddress.getByName("192.0.2.1")
 var hostAddresses=listOf(host!!)
 val attributes=mutableMapOf("device_id" to "agent-a".toByteArray())
}
class NsdManager {
 companion object { const val PROTOCOL_DNS_SD=1 }
 interface DiscoveryListener {
  fun onDiscoveryStarted(type:String); fun onServiceFound(service:NsdServiceInfo); fun onServiceLost(service:NsdServiceInfo)
  fun onDiscoveryStopped(type:String); fun onStartDiscoveryFailed(type:String,code:Int); fun onStopDiscoveryFailed(type:String,code:Int)
 }
 interface ResolveListener { fun onResolveFailed(info:NsdServiceInfo,error:Int); fun onServiceResolved(info:NsdServiceInfo) }
 val scans=CopyOnWriteArrayList<DiscoveryListener>()
 val stopped=CopyOnWriteArrayList<DiscoveryListener>()
 fun discoverServices(type:String,protocol:Int,listener:DiscoveryListener) { scans.add(listener); listener.onDiscoveryStarted(type) }
 fun stopServiceDiscovery(listener:DiscoveryListener) { stopped.add(listener); listener.onDiscoveryStopped("_myautomation._tcp") }
 fun resolveService(info:NsdServiceInfo,listener:ResolveListener) { listener.onServiceResolved(info) }
 fun stopServiceResolution(listener:ResolveListener) {}
}
''',
'Crypto.kt': '''package androidx.security.crypto
import android.content.Context
class MasterKey {
 enum class KeyScheme { AES256_GCM }
 class Builder(context: Context) { fun setKeyScheme(scheme: KeyScheme) = this; fun build() = MasterKey() }
}
object EncryptedSharedPreferences {
 enum class PrefKeyEncryptionScheme { AES256_SIV }
 enum class PrefValueEncryptionScheme { AES256_GCM }
 fun create(context: Context, file: String, key: MasterKey, a: PrefKeyEncryptionScheme, b: PrefValueEncryptionScheme) = context.getSharedPreferences(file, 0)
}
''',
'Serializable.kt': '''package kotlinx.serialization
annotation class Serializable
''',
'Json.kt': '''package kotlinx.serialization.json
class JsonObject
''',
'DebugLogger.kt': '''package com.autonion.automationcompanion.features.automation_debugger
object DebugLogger {
 fun info(vararg args: Any?) {}
 fun success(vararg args: Any?) {}
 fun warning(vararg args: Any?) {}
 fun error(vararg args: Any?) {}
}
''',
'LogCategory.kt': '''package com.autonion.automationcompanion.features.automation_debugger.data
enum class LogCategory { CROSS_DEVICE_SYNC }
''',
'CrossManager.kt': '''package com.autonion.automationcompanion.features.cross_device_automation
import com.autonion.automationcompanion.features.cross_device_automation.networking.NetworkingManager
class CrossDeviceAutomationManager(val networkingManager: NetworkingManager)
''',
'Socket.kt': '''package okhttp3
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
class HttpUrl(val text: String) {
 class Builder {
  private var host=""; private var port=80; private var path=""
  fun scheme(value: String) = this
  fun host(value: String) = apply { require(value.isNotBlank()); host=value }
  fun port(value: Int) = apply { require(value in 1..65535); port=value }
  fun addPathSegment(value: String) = apply { path=value }
  fun build() = HttpUrl("http://$host:$port/$path")
 }
}
class Request(val url: HttpUrl) {
 class Builder { var value=HttpUrl(""); fun url(url: HttpUrl) = apply { value=url }; fun build()=Request(value) }
}
class Response
open class WebSocketListener {
 open fun onOpen(webSocket: WebSocket, response: Response) {}
 open fun onMessage(webSocket: WebSocket, text: String) {}
 open fun onClosing(webSocket: WebSocket, code: Int, reason: String) {}
 open fun onClosed(webSocket: WebSocket, code: Int, reason: String) {}
 open fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {}
}
class WebSocket(val request: Request, val listener: WebSocketListener) {
 interface Factory { fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket }
 val sent = CopyOnWriteArrayList<String>()
 @Volatile var cancelled=false
 @Volatile var closeRequested=false
 fun send(text: String): Boolean { if (cancelled || closeRequested) return false; sent.add(text); return true }
 fun cancel() { cancelled=true }
 fun close(code: Int, reason: String?): Boolean { require(code != 1005) { "Code 1005 is reserved and may not be used." }; closeRequested=true; return true }
 fun open() = listener.onOpen(this,Response())
 fun message(text: String) = listener.onMessage(this,text)
 fun closing(code: Int = 1000) = listener.onClosing(this,code,"closing")
 fun closed(code: Int = 1000) = listener.onClosed(this,code,"closed")
 fun fail() = listener.onFailure(this,java.io.IOException("network down"),null)
}
class OkHttpClient : WebSocket.Factory {
 val sockets=CopyOnWriteArrayList<WebSocket>()
 var failNextCreation=false
 class Builder {
  fun connectTimeout(value:Long,unit:TimeUnit)=this
  fun readTimeout(value:Long,unit:TimeUnit)=this
  fun pingInterval(value:Long,unit:TimeUnit)=this
  fun build()=OkHttpClient()
 }
 override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
  if (failNextCreation) { failNextCreation=false; throw java.io.IOException("socket setup failed") }
  val socket=WebSocket(request,listener); sockets.add(socket); return socket
 }
}
'''
}
for name, content in stubs.items():
    (OUT/name).write_text(content, encoding='utf-8')

cache=Path(os.environ.get('GRADLE_USER_HOME', Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
def jar(group, name, version):
    return next((cache/group/name/version).glob(f'*/{name}-{version}.jar'))
deps=[jar('org.jetbrains.kotlin','kotlin-stdlib','2.2.20'),
      jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.9.0'),
      jar('com.google.code.gson','gson','2.13.2'),jar('org.jetbrains','annotations','23.0.0')]
compiler=deps+[jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.20'),
               jar('org.jetbrains.kotlin','kotlin-script-runtime','2.2.20'),
               jar('org.jetbrains.kotlin','kotlin-reflect','2.2.10')]
sources=[BASE/'cross_device_automation'/p for p in (
    'networking/NetworkingManager.kt','host_management/HostManager.kt','domain/Device.kt','domain/DeviceRepository.kt',
    'data/InMemoryDeviceRepository.kt','data/DeviceAuthManager.kt','domain/Event.kt',
    'domain/PromptResponse.kt','event_pipeline/EventReceiver.kt')]
sources += [BASE/'flow_automation/data/DesktopFlowManager.kt',BASE/'flow_automation/data/DesktopFlowProtocol.kt',Path(__file__).with_name('Regression.kt')]
java_home=Path(os.environ.get('JAVA_HOME','C:/Program Files/Android/Android Studio/jbr'))
java=str(java_home/'bin'/('java.exe' if os.name=='nt' else 'java'))
classpath=os.pathsep.join(map(str,deps))
subprocess.run([java,'-cp',os.pathsep.join(map(str,compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib','-no-reflect','-nowarn','-classpath',classpath,'-d',str(OUT/'tests.jar')]
    + [str(OUT/name) for name in stubs] + list(map(str,sources)),check=True)
result=subprocess.run([java,'-cp',str(OUT/'tests.jar')+os.pathsep+classpath,'RegressionKt'],capture_output=True,text=True,timeout=60)
(OUT/'results.txt').write_text(result.stdout+result.stderr,encoding='utf-8')
print(result.stdout,end='');print(result.stderr,end='')
raise SystemExit(result.returncode)
