package com.autonion.automationcompanion

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.automation_debugger.data.ExecutionLog
import com.autonion.automationcompanion.features.omni_chatbot.data.db.OmniChatSessionEntity
import com.autonion.automationcompanion.features.omni_chatbot.data.db.OmniChatMessageEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.autonion.automationcompanion.core.backup.BackupManager
import com.autonion.automationcompanion.core.backup.BackupManifest
import com.autonion.automationcompanion.core.backup.CryptoUtils
import com.autonion.automationcompanion.features.screen_understanding_ml.logic.PresetRepository
import com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationPreset
import com.autonion.automationcompanion.features.flow_automation.data.FlowRepository
import com.autonion.automationcompanion.features.flow_automation.model.FlowGraph
import com.autonion.automationcompanion.features.flow_automation.model.ScreenMLNode
import com.google.gson.Gson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Regression checks for old/new backups, assets, collisions and live Room consumers. */
@RunWith(AndroidJUnit4::class)
class BackupCompatibilityTest {
    private val legacy = """{"id":"legacy","name":"Old login","scope":"APP_SPECIFIC",
        "targetPackageName":"example.target","executionMode":"STRICT","createdAt":1234,
        "steps":[{"id":"step","orderIndex":0,"label":"Continue","actionType":"CLICK",
        "anchor":{"id":"anchor","label":"Button","confidence":1.0,
        "bounds":{"left":10,"top":20,"right":100,"bottom":80},"text":"Continue","source":"accessibility"},
        "isOptional":false,"captureScreenWidth":1080,"captureScreenHeight":2400}]}"""

    @Test fun legacyArchiveWithoutScreenshotRestoresForPlainAndEncryptedBackups() = Fixture().use { f ->
        for (password in listOf(null, "audit-password")) {
            val raw = archive(mapOf("ml_presets/legacy.json" to legacy.toByteArray()), encrypted = password != null)
            val input = File(f.root, "legacy-${password != null}.atnbak")
            if (password == null) input.writeBytes(raw)
            else input.outputStream().use { CryptoUtils.encrypt(ByteArrayInputStream(raw), it, password) }
            val manager = BackupManager(f.target)
            if (password != null) {
                assertTrue(manager.import(Uri.fromFile(input), null) is BackupManager.ImportResult.NeedsPassword)
                assertTrue(manager.import(Uri.fromFile(input), "wrong") is BackupManager.ImportResult.WrongPassword)
            }
            assertTrue(manager.import(Uri.fromFile(input), password) is BackupManager.ImportResult.Success)
            val preset = PresetRepository(f.target).getPreset("legacy")!!
            assertNull(preset.steps.single().captureImagePath)
            assertEquals("Continue", preset.steps.single().anchor.text)
            assertEquals(com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType.CLICK, preset.steps.single().actionType)
            assertEquals(10f, preset.steps.single().anchor.bounds.left, 0f)
            PresetRepository(f.target).savePreset(preset.copy(name = "Edited legacy"))
            assertEquals("Edited legacy", PresetRepository(f.target).getPreset("legacy")!!.name)
        }
    }

    @Test fun newScreenMlBackupIncludesSavedSnapshotSubdirectories() = Fixture().use { f ->
        val image = File(f.root, "capture.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val old = Gson().fromJson(legacy, AutomationPreset::class.java)
        val repository = PresetRepository(f.source)
        repository.savePreset(old.copy(steps = old.steps.map { it.copy(captureImagePath = image.absolutePath) }))
        val savedImage = File(repository.getPreset("legacy")!!.steps.single().captureImagePath!!)
        val output = File(f.root, "new.atnbak")
        assertTrue(BackupManager(f.source).export(Uri.fromFile(output), listOf(BackupManifest.FEATURE_ML_PRESETS), null))
        ZipFile(output).use { zip ->
            assertNotNull("New Screen ML snapshot is missing from exported archive", zip.getEntry(savedImage.relativeTo(f.source.filesDir).invariantSeparatorsPath))
        }
    }

    @Test fun encryptedSnapshotRoundTripSurvivesSourceRemovalAndRepeatedImport() = Fixture().use { f ->
        val image = File(f.root, "capture.png").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val preset = Gson().fromJson(legacy, AutomationPreset::class.java)
        val repository = PresetRepository(f.source)
        repository.savePreset(preset.copy(steps = preset.steps.map { it.copy(captureImagePath = image.absolutePath) }))
        val output = File(f.root, "encrypted.atnbak")
        assertTrue(BackupManager(f.source).export(Uri.fromFile(output), listOf(BackupManifest.FEATURE_ML_PRESETS), "secret"))
        repository.deletePreset(preset.id)
        image.delete()
        val manager = BackupManager(f.target)
        assertTrue(manager.import(Uri.fromFile(output), "secret") is BackupManager.ImportResult.Success)
        val targetRepository = PresetRepository(f.target)
        val restored = targetRepository.getPreset(preset.id)!!
        assertArrayEquals(byteArrayOf(7, 8, 9), File(restored.steps.single().captureImagePath!!).readBytes())
        targetRepository.savePreset(restored.copy(name = "My newer edit"))
        val repeated = manager.import(Uri.fromFile(output), "secret") as BackupManager.ImportResult.Success
        assertEquals(1, repeated.skipped)
        assertEquals("My newer edit", targetRepository.getPreset(preset.id)!!.name)
    }

    @Test fun invalidLaterPresetDoesNotPublishEarlierValidPreset() = Fixture().use { f ->
        val input = File(f.root, "invalid.atnbak").apply {
            writeBytes(archive(mapOf("ml_presets/legacy.json" to legacy.toByteArray(), "vision_presets/broken.json" to "not JSON".toByteArray())))
        }
        assertTrue(BackupManager(f.target).import(Uri.fromFile(input), null) is BackupManager.ImportResult.Error)
        assertFalse(File(f.target.filesDir, "ml_presets/legacy.json").exists())
    }

    @Test fun assetCollisionPreservesCurrentImageAndRemapsIncomingPreset() = Fixture().use { f ->
        val current = File(f.target.filesDir, "viz_capture.png").apply { writeBytes(byteArrayOf(1)) }
        val incoming = """{"id":"new","name":"Imported vision","captureImagePath":"/old/files/viz_capture.png"}"""
        val input = File(f.root, "collision-images.atnbak").apply {
            writeBytes(archive(mapOf("vision_presets/new.json" to incoming.toByteArray(), "vision_images/viz_capture.png" to byteArrayOf(2))))
        }
        assertTrue(BackupManager(f.target).import(Uri.fromFile(input), null) is BackupManager.ImportResult.Success)
        assertArrayEquals(byteArrayOf(1), current.readBytes())
        val preset = Gson().fromJson(File(f.target.filesDir, "vision_presets/new.json").readText(), Map::class.java)
        val restoredImage = File(preset["captureImagePath"] as String)
        assertNotEquals(current.absolutePath, restoredImage.absolutePath)
        assertArrayEquals(byteArrayOf(2), restoredImage.readBytes())
    }

    @Test fun invalidImageEntryCannotBypassPresetValidationOrPublishFiles() = Fixture().use { f ->
        for (entry in listOf("vision_images/presets/unvalidated.json", "ml_presets//alias/snapshot.png", "flow_assets/../escape.png")) {
            val input = File(f.root, "invalid-image.atnbak").apply {
                writeBytes(archive(mapOf("ml_presets/legacy.json" to legacy.toByteArray(), entry to "invalid".toByteArray())))
            }
            assertTrue(entry, BackupManager(f.target).import(Uri.fromFile(input), null) is BackupManager.ImportResult.Error)
            assertTrue("Invalid archive must not publish any files", f.target.filesDir.walkTopDown().none { it.isFile })
        }
    }

    @Test fun fullFlowBackupCollectsPerStepImagesOutsideFlowAssets() = Fixture().use { f ->
        val image = File(f.source.filesDir, "temporary-snapshot.png").apply { writeBytes(byteArrayOf(4, 5)) }
        val steps = Json.encodeToString(Gson().fromJson(legacy, AutomationPreset::class.java).steps.map { it.copy(captureImagePath = image.absolutePath) })
        val graph = FlowGraph(name = "Nested image", nodes = listOf(ScreenMLNode(automationStepsJson = steps)))
        FlowRepository(f.source).save(graph)
        val output = File(f.root, "flow-full.atnbak")
        assertTrue(BackupManager(f.source).export(Uri.fromFile(output), listOf(BackupManifest.FEATURE_FLOWS), null))
        image.delete()
        assertTrue(BackupManager(f.target).import(Uri.fromFile(output), null) is BackupManager.ImportResult.Success)
        val restored = FlowRepository(f.target).load(graph.id)!!.nodes.single() as ScreenMLNode
        val path = (Gson().fromJson(restored.automationStepsJson, List::class.java).single() as Map<*, *>)["captureImagePath"] as String
        assertArrayEquals(byteArrayOf(4, 5), File(path).readBytes())
    }

    @Test fun globalBackupRebasesVisionPathsForDestinationStorage() = Fixture().use { f ->
        val sourceImage = File(f.source.filesDir, "viz_capture.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val presetJson = """{"id":"vision","name":"Vision","captureImagePath":"${sourceImage.absolutePath}"}"""
        File(f.source.filesDir, "vision_presets").mkdirs()
        File(f.source.filesDir, "vision_presets/vision.json").writeText(presetJson)
        val output = File(f.root, "vision.atnbak")
        assertTrue(BackupManager(f.source).export(Uri.fromFile(output), listOf(BackupManifest.FEATURE_VISION_PRESETS, BackupManifest.FEATURE_VISION_IMAGES), null))
        assertTrue(sourceImage.delete())
        assertTrue(BackupManager(f.target).import(Uri.fromFile(output), null) is BackupManager.ImportResult.Success)
        assertTrue(File(f.target.filesDir, "viz_capture.png").isFile)
        val restored = Gson().fromJson(File(f.target.filesDir, "vision_presets/vision.json").readText(), Map::class.java)
        assertEquals("Imported image path must reference restored destination", File(f.target.filesDir, "viz_capture.png").absolutePath, restored["captureImagePath"])
    }

    @Test fun individualFlowImportRebasesSnapshotInsideAutomationSteps() = Fixture().use { f ->
        val sourceImage = File(f.source.filesDir, "snapshot.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val steps = Json.encodeToString(Gson().fromJson(legacy, AutomationPreset::class.java).steps.map { it.copy(captureImagePath = sourceImage.absolutePath) })
        val graph = FlowGraph(name = "Screen ML flow", nodes = listOf(ScreenMLNode(captureImagePath = sourceImage.absolutePath, automationStepsJson = steps)))
        val sourceRepository = FlowRepository(f.source)
        sourceRepository.save(graph)
        val output = File(f.root, "flow.zip")
        assertTrue(sourceRepository.exportToUri(graph.id, Uri.fromFile(output)))
        assertTrue(sourceImage.delete())
        val imported = FlowRepository(f.target).importFromUri(Uri.fromFile(output))!!
        val node = imported.nodes.single() as ScreenMLNode
        assertTrue(File(node.captureImagePath).isFile)
        val nested = Gson().fromJson(node.automationStepsJson, List::class.java).single() as Map<*, *>
        assertEquals("Per-step snapshot must be remapped along with node snapshot", node.captureImagePath, nested["captureImagePath"])
    }

    @Test fun globalImportPreservesExistingSameNamePresetAsPromisedByUi() = Fixture().use { f ->
        File(f.target.filesDir, "presets").mkdirs()
        val existing = File(f.target.filesDir, "presets/Gesture.json").apply { writeText("""[{"id":1,"type":"WAIT","duration":200}]""") }
        val input = File(f.root, "collision.atnbak").apply { writeBytes(archive(mapOf("presets/Gesture.json" to """[{"id":1,"type":"WAIT","duration":100}]""".toByteArray()))) }
        assertTrue(BackupManager(f.target).import(Uri.fromFile(input), null) is BackupManager.ImportResult.Success)
        assertEquals("Existing same-name preset must not silently be replaced", """[{"id":1,"type":"WAIT","duration":200}]""", existing.readText())
    }

    @Test fun nestedScreenMlAssetsCanBeRestoredFromACompleteArchive() = Fixture().use { f ->
        val input = File(f.root, "nested.atnbak").apply {
            writeBytes(archive(mapOf("ml_presets/legacy.json" to legacy.toByteArray(), "ml_presets/legacy_images/snapshot.png" to byteArrayOf(1, 2, 3))))
        }
        assertTrue("Import must create snapshot parent directories", BackupManager(f.target).import(Uri.fromFile(input), null) is BackupManager.ImportResult.Success)
        assertTrue(File(f.target.filesDir, "ml_presets/legacy_images/snapshot.png").isFile)
    }

    @Test fun databaseRestoreKeepsExistingRowsAndLiveDaosAndAddsMissingRows() = Fixture().use { f ->
        val instanceField = AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        val previous = instanceField.get(null)
        val db = Room.databaseBuilder(f.target, AppDatabase::class.java, f.target.getDatabasePath("locauto.db").absolutePath).build()
        try {
            instanceField.set(null, db)
            val cachedDao = db.slotDao()
            runBlocking {
                cachedDao.insert(com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot(id = 42, triggerType = "APP", actions = emptyList(), enabled = false))
                cachedDao.insert(com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot(id = 43, triggerType = "APP", actions = emptyList(), enabled = false, isInsideGeofence = true, lastExecutedDay = "2026-01-01", lastTriggerState = true))
                assertFalse(cachedDao.getById(42)!!.enabled)
                for ((index, module) in listOf("omni", "semantic", "cross_device").withIndex()) {
                    val id = "conversation-$index"
                    db.backupDao().session(OmniChatSessionEntity(id, "Saved $module", 1234, "preview", module))
                    db.backupDao().message(OmniChatMessageEntity("message-$index", id, "Saved response", false, "AUTO", 1234, null, null))
                }
                db.backupDao().log(ExecutionLog(51, 1234, "SYSTEM_CONTEXT", "INFO", "Saved log", "Details", "BackupTest"))
                db.backupDao().log(ExecutionLog(52, 1235, "FLOW_BUILDER", "INFO", "Other log", "Details", "BackupTest"))
            }
            val output = File(f.root, "database.atnbak")
            assertTrue(BackupManager(f.target).export(Uri.fromFile(output), listOf(BackupManifest.FEATURE_SYSTEM_CONTEXT_DB), null))
            val exportedDb = File(f.root, "exported.db")
            ZipFile(output).use { zip -> zip.getInputStream(zip.getEntry("database/locauto.db")).use { input -> exportedDb.outputStream().use { input.copyTo(it) } } }
            fun onDiskEnabled(file: File): Int = android.database.sqlite.SQLiteDatabase.openDatabase(file.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                sqlite.rawQuery("SELECT enabled FROM slots WHERE id=42", null).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }
            }
            assertEquals("Export must contain the original disabled row", 0, onDiskEnabled(exportedDb))
            runBlocking {
                cachedDao.setEnabled(42, true)
                cachedDao.delete(cachedDao.getById(43)!!)
                db.omniChatDao().updateSessionMetadata("conversation-0", "Current title", "Current preview", 5678)
                db.omniChatDao().insertMessage(OmniChatMessageEntity("message-0", "conversation-0", "Current response", false, "AUTO", 5678, null, null))
                db.omniChatDao().deleteSession("conversation-1")
                db.omniChatDao().deleteSession("conversation-2")
                db.executionLogDao().deleteAll()
                db.backupDao().log(ExecutionLog(51, 5678, "SYSTEM_CONTEXT", "INFO", "Current log", "Details", "BackupTest"))
            }
            val result = BackupManager(f.target).import(Uri.fromFile(output), null)
            assertTrue(result.toString(), result is BackupManager.ImportResult.Success)
            runBlocking { withTimeout(5000) {
                assertSame(db, AppDatabase.get(f.target))
                assertTrue("Current matching row must win", cachedDao.getById(42)!!.enabled)
                val added = cachedDao.getById(43)!!
                assertFalse(added.enabled)
                assertFalse(added.isInsideGeofence)
                assertNull(added.lastExecutedDay)
                assertNull(added.lastTriggerState)
                cachedDao.setEnabled(43, true)
                assertTrue(cachedDao.getById(43)!!.enabled)
                val sessions = db.backupDao().sessions().associateBy { it.sessionId }
                assertEquals(3, sessions.size)
                assertEquals("Current title", sessions.getValue("conversation-0").title)
                assertEquals("Saved semantic", sessions.getValue("conversation-1").title)
                assertEquals("cross_device", sessions.getValue("conversation-2").module)
                val messages = db.backupDao().messages().associateBy { it.messageId }
                assertEquals(3, messages.size)
                assertEquals("Current response", messages.getValue("message-0").text)
                assertEquals("Saved response", messages.getValue("message-1").text)
                assertEquals("Saved response", messages.getValue("message-2").text)
                val logs = db.backupDao().logs().associateBy { it.id }
                assertEquals(2, logs.size)
                assertEquals("Current log", logs.getValue(51).title)
                assertEquals("Other log", logs.getValue(52).title)
            } }
        } finally {
            db.close()
            instanceField.set(null, previous)
        }
    }

    private fun archive(entries: Map<String, ByteArray>, encrypted: Boolean = false): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            val manifest = BackupManifest("1.1.2", 1234, encrypted, BackupManifest.ALL_FEATURES)
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(Json.encodeToString(manifest).toByteArray()); zip.closeEntry()
            for ((name, bytes) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }.toByteArray()

    private class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "backup-audit-${UUID.randomUUID()}").apply { mkdirs() }
        val source = storage("source")
        val target = storage("target")
        private fun storage(name: String) = object : ContextWrapper(context) {
            override fun getFilesDir(): File = File(root, name).apply { mkdirs() }
            override fun getDatabasePath(name: String): File = (if (File(name).isAbsolute) File(name) else File(filesDir, "databases/$name")).apply { parentFile!!.mkdirs() }
        }
        override fun close() { check(root.name.startsWith("backup-audit-")); root.deleteRecursively() }
    }
}

