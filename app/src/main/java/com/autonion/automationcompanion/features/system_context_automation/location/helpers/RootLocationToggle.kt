//package com.example.automationcompanion.core.helpers
//
//import android.content.Context
//import android.location.LocationManager
//import android.util.Log
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//import java.io.BufferedReader
//import java.io.InputStreamReader
//import kotlin.time.Duration
//import kotlin.time.Duration.Companion.seconds
//
//object RootLocationToggle {
//    private const val TAG = "RootLocationToggle"
//
//    // ---- Run "su -c <cmd>" and return (exitCode, stdoutText) ----
//    private suspend fun execSu(cmd: String): Pair<Int, String> = withContext(Dispatchers.IO) {
//        return@withContext try {
//            val full = arrayOf("su", "-c", cmd)
//            val proc = ProcessBuilder(*full).redirectErrorStream(true).start()
//            val out = StringBuilder()
//            BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
//                var line = br.readLine()
//                while (line != null) {
//                    out.append(line).append('\n')
//                    line = br.readLine()
//                }
//            }
//            val exit = proc.waitFor()
//            Pair(exit, out.toString())
//        } catch (t: Throwable) {
//            Log.w(TAG, "execSu failed: ${t.message}", t)
//            Pair(-1, t.message ?: "err")
//        }
//    }
//
//    /** quick non-privileged check: try running 'id' via su */
//    suspend fun isSuAvailable(): Boolean = withContext(Dispatchers.IO) {
//        val (code, out) = execSu("id")
//        if (code != 0) {
//            Log.i(TAG, "su id returned code=$code out=$out")
//        }
//        return@withContext code == 0 && out.contains("uid")
//    }
//
//    /** Read secure location_mode using su (returns 0..3 or null on failure) */
//    suspend fun getLocationModeViaSu(): Int? = withContext(Dispatchers.IO) {
//        val (code, out) = execSu("settings get secure location_mode")
//        if (code != 0) {
//            Log.w(TAG, "getLocationModeViaSu failed code=$code out=$out")
//            return@withContext null
//        }
//        val trimmed = out.trim()
//        return@withContext try {
//            trimmed.toInt()
//        } catch (_: Throwable) {
//            null
//        }
//    }
//
//    /** Set secure location_mode via su. mode should be 0..3. Returns true if command exit code == 0. */
//    suspend fun setLocationModeViaSu(mode: Int): Boolean = withContext(Dispatchers.IO) {
//        val m = mode.coerceIn(0, 3)
//        val (code, out) = execSu("settings put secure location_mode $m")
//        if (code != 0) {
//            Log.w(TAG, "setLocationModeViaSu($m) failed code=$code out=$out")
//        }
//        return@withContext code == 0
//    }
//
//    /**
//     * Enable location silently (attempt). Returns previous mode on success (or null on failure).
//     * It will set location_mode to 3 (HIGH_ACCURACY) and poll providers until they report enabled or
//     * until timeout. Timeout default is 10 seconds.
//     */
//    suspend fun enableLocationSilently(context: Context, timeout: Duration = 10.seconds): Int? = withContext(Dispatchers.IO) {
//        val prev = getLocationModeViaSu()
//        if (prev == null) {
//            Log.w(TAG, "enableLocationSilently: cannot read previous mode")
//            return@withContext null
//        }
//
//        val ok = setLocationModeViaSu(3)
//        if (!ok) {
//            Log.w(TAG, "enableLocationSilently: failed to set mode=3")
//            return@withContext null
//        }
//
//        // poll until providers visible or timeout
//        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
//        while (System.currentTimeMillis() < deadline) {
//            if (isLocationServicesEnabled(context)) return@withContext prev
//            Thread.sleep(400L)
//        }
//
//        // timed out — still return prev so caller can attempt restore
//        return@withContext prev
//    }
//
//    /** Restore location_mode via su; returns whether set succeeded. */
//    suspend fun restoreLocationMode(prevMode: Int): Boolean = withContext(Dispatchers.IO) {
//        setLocationModeViaSu(prevMode)
//    }
//
//    /** Non-root check for whether any provider is enabled */
//    fun isLocationServicesEnabled(context: Context): Boolean {
//        return try {
//            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
//            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
//        } catch (t: Throwable) {
//            Log.w(TAG, "isLocationServicesEnabled failed: ${t.message}", t)
//            false
//        }
//    }
//}
