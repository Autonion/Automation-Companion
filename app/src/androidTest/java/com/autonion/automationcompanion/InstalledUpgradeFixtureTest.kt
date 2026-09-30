package com.autonion.automationcompanion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in fixture for an APK update; uses framework data access instead of application classes. */
@RunWith(AndroidJUnit4::class)
class InstalledUpgradeFixtureTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("installed_upgrade_test", Context.MODE_PRIVATE)
    private fun requireOptIn() = assumeTrue("Only run on a disposable upgrade-test installation",
        InstrumentationRegistry.getArguments().getString("upgradeFixture") == "true")

    @Test fun seedPreviousVersion() {
        requireOptIn()
        assertEquals("1.1.1", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        val file = context.getDatabasePath("locauto.db")
        assertTrue("Launch the old app once to create its actual database", file.isFile)
        val saved = JSONArray()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("SELECT count(*) FROM slots", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            val configs = linkedMapOf(
                "LOCATION" to "",
                "BATTERY" to """{"type":"battery","batteryPercentage":0,"thresholdType":"reaches_or_below"}""",
                "TIME_OF_DAY" to """{"type":"time_of_day","hour":23,"minute":59,"repeatDaily":true,"activeDays":"ALL"}""",
                "WIFI" to """{"type":"wifi","connectionState":"connected","optionalSsid":"upgrade-test-network"}""",
                "APP" to """{"type":"app","packageName":"example.upgrade","triggerOn":"open"}"""
            )
            for ((type, config) in configs) for (enabled in listOf(0, 1)) {
                val row = ContentValues().apply {
                    put("triggerType", type)
                    if (config.isEmpty()) putNull("triggerConfigJson") else put("triggerConfigJson", config)
                    put("lat", 12.9); put("lng", 77.5); put("radiusMeters", 200f)
                    put("startMillis", System.currentTimeMillis() + 3_600_000)
                    put("endMillis", System.currentTimeMillis() + 7_200_000)
                    put("remindBeforeMinutes", 0); put("actions", "[]"); put("enabled", enabled)
                    put("activeDays", "ALL"); put("isInsideGeofence", 0)
                }
                val id = db.insertOrThrow("slots", null, row)
                saved.put(JSONObject().put("id", id).put("type", type).put("config", config).put("enabled", enabled))
            }
            db.execSQL("INSERT INTO omni_chat_sessions (sessionId,title,timestamp,previewText,module) VALUES ('upgrade-session','Previous chat',1234,'Previous preview','semantic')")
            db.execSQL("INSERT INTO omni_chat_messages VALUES ('upgrade-message','upgrade-session','Previous answer',0,'AUTO',1234,NULL,NULL)")
            // DebugLogger intentionally prunes logs older than seven days on every write.
            db.execSQL("INSERT INTO execution_logs (timestamp,category,level,title,message,source) VALUES (?,'SYSTEM_CONTEXT','INFO','Upgrade fixture','Previous log','InstalledUpgradeFixture')",
                arrayOf<Any>(System.currentTimeMillis() - 60_000))
        }
        val legacy = """{"id":"upgrade-fixture","name":"Previous UI preset","scope":"APP_SPECIFIC","targetPackageName":"example.upgrade","executionMode":"STRICT","createdAt":1234,"steps":[]}"""
        File(context.filesDir, "ml_presets").mkdirs()
        File(context.filesDir, "ml_presets/upgrade-fixture.json").writeText(legacy)
        assertTrue(prefs.edit().putString("rows", saved.toString()).putString("legacy", legacy).putString("sentinel", "keep-through-update").commit())
    }

    @Test fun verifyAfterInstalledUpdate() {
        requireOptIn()
        assertEquals("1.1.4", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        assertEquals("keep-through-update", prefs.getString("sentinel", null))
        val saved = JSONArray(prefs.getString("rows", null)!!)
        assertEquals(10, saved.length())
        SQLiteDatabase.openDatabase(context.getDatabasePath("locauto.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            for (index in 0 until saved.length()) {
                val expected = saved.getJSONObject(index)
                db.rawQuery("SELECT * FROM slots WHERE id=?", arrayOf(expected.getLong("id").toString())).use { cursor ->
                    assertTrue("Missing preset after update", cursor.moveToFirst())
                    assertEquals(expected.getString("type"), cursor.getString(cursor.getColumnIndexOrThrow("triggerType")))
                    assertEquals(expected.getInt("enabled"), cursor.getInt(cursor.getColumnIndexOrThrow("enabled")))
                    assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("actions")))
                    if (expected.getString("type") != "LOCATION") assertEquals(expected.getString("config"), cursor.getString(cursor.getColumnIndexOrThrow("triggerConfigJson")))
                    assertEquals(12.9, cursor.getDouble(cursor.getColumnIndexOrThrow("lat")), 0.001)
                }
            }
            db.rawQuery("SELECT title,module FROM omni_chat_sessions WHERE sessionId='upgrade-session'", null).use {
                assertTrue(it.moveToFirst()); assertEquals("Previous chat", it.getString(0)); assertEquals("semantic", it.getString(1))
            }
            db.rawQuery("SELECT text FROM omni_chat_messages WHERE messageId='upgrade-message'", null).use { assertTrue(it.moveToFirst()); assertEquals("Previous answer", it.getString(0)) }
            db.rawQuery("SELECT message FROM execution_logs WHERE source='InstalledUpgradeFixture'", null).use { assertTrue(it.moveToFirst()); assertEquals("Previous log", it.getString(0)) }
        }
        assertEquals(prefs.getString("legacy", null), File(context.filesDir, "ml_presets/upgrade-fixture.json").readText())
    }
}
