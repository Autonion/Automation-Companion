package com.autonion.automationcompanion.core.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.autonion.automationcompanion.core.settings.ExclusionManager
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAutomationController
import com.autonion.automationcompanion.features.system_context_automation.timeofday.engine.TimeOfDayReceiver
import com.autonion.automationcompanion.features.system_context_automation.battery.engine.BatteryServiceManager
import com.autonion.automationcompanion.features.system_context_automation.wifi.engine.WiFiMonitorManager
import com.autonion.automationcompanion.features.gesture_recording_playback.models.Action
import com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset
import com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationPreset
import com.autonion.automationcompanion.features.flow_automation.model.FlowGraph
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val MANIFEST = "manifest.json"
private const val DATABASE = "database/locauto.db"
private const val EXCLUSIONS = "preferences/exclusion_list.json"

@Serializable
internal data class BackupExclusions(val excluded_packages: List<String>, val strict_mode: Boolean = false)

/** Stages and validates archives before merging them. Existing items always win collisions. */
class BackupManager(private val context: Context) {
    var lastExportWarnings: List<String> = emptyList()
        private set
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val directories = mapOf(
        BackupManifest.FEATURE_GESTURE_PRESETS to "presets",
        BackupManifest.FEATURE_VISION_PRESETS to "vision_presets",
        BackupManifest.FEATURE_ML_PRESETS to "ml_presets",
        BackupManifest.FEATURE_FLOWS to "flows",
        BackupManifest.FEATURE_FLOW_ASSETS to "flow_assets"
    )

    fun export(uri: Uri, features: List<String>, password: String?, onProgress: (Int) -> Unit = {}): Boolean = synchronized(operationLock) {
        val work = workspace()
        try {
            lastExportWarnings = emptyList()
            onProgress(0)
            val files = linkedMapOf<String, File>()
            directories.forEach { (feature, directory) ->
                if (feature in features) {
                    val root = File(context.filesDir, directory)
                    if (root.isDirectory) root.walkTopDown().filter { it.isFile }.forEach { file ->
                        val name = "$directory/${file.relativeTo(root).invariantSeparatorsPath}"
                        if (!name.endsWith(".bak") && !name.endsWith(".new")) files[name] = file
                    }
                }
            }
            if (BackupManifest.FEATURE_VISION_IMAGES in features) {
                context.filesDir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("viz_") && it.extension == "png" }
                    .forEach { files["vision_images/${it.name}"] = it }
            }
            val texts = files.filterKeys(::isPreset).mapValues { (name, file) ->
                readJson(file).also { validatePreset(name, it) }
            }
            val warnings = mutableListOf<String>()
            val paths = files.entries.associate { it.value.canonicalPath to it.key }.toMutableMap()
            val extraPrefix = "flow_assets/backup_${UUID.randomUUID()}"
            for (text in texts.values) for (path in BackupImagePaths.collect(text)) {
                val source = File(path)
                if (source.isFile && (inside(context.filesDir, source) || inside(context.cacheDir, source))) {
                    val canonical = source.canonicalPath
                    if (canonical !in paths) {
                        val name = "$extraPrefix/${paths.size}_${source.name}"
                        files[name] = source
                        paths[canonical] = name
                    }
                } else warnings.add("A capture file was unavailable when this backup was created: ${source.name}")
            }
            val portable = texts.mapValues { (_, text) ->
                BackupImagePaths.rewrite(text) { paths[File(it).canonicalPath] ?: "" }
            }
            if (BackupManifest.FEATURE_SYSTEM_CONTEXT_DB in features) {
                val snapshot = File(work, "snapshot.db")
                runBlocking { BackupDatabase.snapshot(context, snapshot) }
                files[DATABASE] = snapshot
            }
            val included = features.toMutableSet()
            if (files.keys.any { it.startsWith("flow_assets/") }) included.add(BackupManifest.FEATURE_FLOW_ASSETS)
            val manifest = BackupManifest(getAppVersion(), System.currentTimeMillis(), !password.isNullOrBlank(),
                included.toList(), formatVersion = 2, warnings = warnings.distinct())
            require(files.size + 2 <= 20_000 && files.values.sumOf { it.length() } < MAX_ARCHIVE_BYTES - 16L * 1024 * 1024) {
                "Backup exceeds the supported archive size"
            }
            val archive = File(work, "backup.zip")
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                files.entries.forEachIndexed { index, (name, file) ->
                    zip.putNextEntry(ZipEntry(name))
                    portable[name]?.let { zip.write(it.toByteArray(Charsets.UTF_8)) }
                        ?: file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    onProgress(10 + (index * 65 / files.size.coerceAtLeast(1)))
                }
                if (BackupManifest.FEATURE_EXCLUDED_APPS in features) {
                    zip.putNextEntry(ZipEntry(EXCLUSIONS))
                    zip.write(ExclusionManager.exportToJson().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open backup destination")
            output.use { destination -> archive.inputStream().use { input ->
                if (password.isNullOrBlank()) input.copyTo(destination) else CryptoUtils.encrypt(input, destination, password)
            } }
            onProgress(100)
            lastExportWarnings = warnings.distinct()
            true
        } catch (error: Exception) {
            Log.e("BackupManager", "Export failed", error)
            false
        } finally { work.deleteRecursively() }
    }

    sealed class ImportResult {
        data class Success(val manifest: BackupManifest, val imported: Int = 0, val skipped: Int = 0,
            val warnings: List<String> = emptyList()) : ImportResult()
        data class NeedsPassword(val dummy: Unit = Unit) : ImportResult()
        data class WrongPassword(val message: String) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    fun import(uri: Uri, password: String?, onProgress: (Int) -> Unit = {}): ImportResult = synchronized(operationLock) {
        val work = workspace()
        val created = mutableListOf<File>()
        var committed = false
        try {
            onProgress(0)
            val archive = File(work, "input.zip")
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open backup file")
            input.buffered().use { buffered ->
                buffered.mark(4)
                val header = ByteArray(4)
                val count = buffered.read(header)
                buffered.reset()
                if (count == 4 && CryptoUtils.isEncrypted(header)) {
                    if (password.isNullOrBlank()) return@synchronized ImportResult.NeedsPassword()
                    archive.outputStream().use { CryptoUtils.decrypt(buffered, it, password) }
                } else archive.outputStream().use { copyLimited(buffered, it, MAX_ARCHIVE_BYTES) }
            }
            val staged = extract(archive, File(work, "entries"))
            val manifest = staged[MANIFEST]?.let { json.decodeFromString<BackupManifest>(readJson(it)) }
                ?: error("Invalid backup: missing manifest")
            require(manifest.formatVersion in 1..2) { "This backup requires a newer app version" }
            val warnings = manifest.warnings.toMutableList()
            onProgress(25)
            val texts = staged.filterKeys(::isPreset).mapValues { (name, file) ->
                readJson(file).also { validatePreset(name, it) }
            }
            val exclusions = staged[EXCLUSIONS]?.let { json.decodeFromString<BackupExclusions>(readJson(it)) }
            val rows = staged[DATABASE]?.let { runBlocking { BackupDatabase.readArchive(context, it) } }
            val destinations = mutableMapOf<String, File>()
            val writes = linkedMapOf<File, File>()
            var skipped = 0
            var imported = 0
            val accepted = texts.filterKeys { name ->
                val target = safeFile(context.filesDir, name)
                val exists = target.exists() || (name.startsWith("presets/") &&
                    target.parentFile?.listFiles().orEmpty().any { it.name.equals(target.name, ignoreCase = true) })
                if (exists) skipped++
                !exists
            }
            val assetNames = staged.keys.filter(::isAsset).toSet()
            fun references(values: Collection<String>) = values.flatMap { BackupImagePaths.collect(it) }
                .mapNotNull { resolveImage(it, assetNames) }.toSet()
            val allReferences = references(texts.values)
            val neededReferences = references(accepted.values)
            staged.filterKeys { isAsset(it) && (it !in allReferences || it in neededReferences) }.forEach { (name, source) ->
                val relative = if (name.startsWith("vision_images/")) name.removePrefix("vision_images/") else name
                var target = safeFile(context.filesDir, relative)
                if (target.exists() && !sameBytes(source, target)) {
                    target = File(target.parentFile, "${UUID.randomUUID()}_${target.name}")
                }
                destinations[name] = target
                if (!target.exists()) writes[target] = source
            }
            accepted.forEach { (name, text) ->
                val target = safeFile(context.filesDir, name)
                val remapped = BackupImagePaths.rewrite(text) { path ->
                    val entry = resolveImage(path, destinations.keys)
                    if (entry != null) destinations.getValue(entry).absolutePath
                    else if (File(path).isFile && inside(context.filesDir, File(path))) File(path).absolutePath
                    else {
                        warnings.add("An imported preset has unavailable capture data; recapture it to restore the missing data.")
                        ""
                    }
                }
                validatePreset(name, remapped)
                val prepared = safeFile(File(work, "prepared"), name)
                prepared.parentFile!!.mkdirs()
                prepared.writeText(remapped)
                writes[target] = prepared
            }
            onProgress(50)
            fun publishFiles() {
                writes.forEach { (target, source) ->
                    target.parentFile!!.mkdirs()
                    if (!target.createNewFile()) {
                        if (isPreset(target.relativeTo(context.filesDir).invariantSeparatorsPath)) skipped++
                        else error("An image destination changed during import; please retry")
                    } else {
                        created.add(target)
                        source.inputStream().use { inputStream -> target.outputStream().use { inputStream.copyTo(it) } }
                        imported++
                    }
                }
            }
            if (rows != null) runBlocking {
                LocationAutomationController.mutex.withLock {
                    val live = AppDatabase.get(context)
                    // Capture this installation's old IDs before imported IDs affect sqlite_sequence.
                    com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationGeofenceRegistry.prepareLegacyCleanup(context)
                    live.withTransaction {
                        publishFiles()
                        val counts = BackupDatabase.merge(live, rows, warnings)
                        imported += counts.first
                        skipped += counts.second
                    }
                }
            } else publishFiles()
            committed = true
            if (exclusions != null) {
                try { ExclusionManager.mergeFromBackup(context, exclusions.excluded_packages.toSet(), exclusions.strict_mode) }
                catch (error: Exception) { warnings.add("Presets were imported, but excluded-app settings could not be saved.") }
            }
            if (rows != null) runBlocking {
                try { LocationAutomationController.reconcile(context, resetPresence = true) }
                catch (error: Exception) { warnings.add("Location monitoring needs attention; reopen the app and check location permissions.") }
                try {
                    com.autonion.automationcompanion.features.system_context_automation.shared.SystemSlotController.recover(context)
                } catch (error: Exception) { warnings.add("Some system automations need to be reopened to resume monitoring.") }
            }
            onProgress(100)
            ImportResult.Success(manifest, imported, skipped, warnings.distinct())
        } catch (error: WrongPasswordException) {
            ImportResult.WrongPassword(error.message ?: "Incorrect password")
        } catch (error: Exception) {
            Log.e("BackupManager", "Import failed", error)
            ImportResult.Error(error.message ?: "Could not import backup")
        } finally {
            if (!committed) created.asReversed().forEach { it.delete() }
            work.deleteRecursively()
        }
    }

    private fun extract(archive: File, root: File): Map<String, File> {
        val files = linkedMapOf<String, File>()
        var total = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = safeFile(root, entry.name)
                if (!entry.isDirectory) {
                    require(files.size < 20_000 && entry.name !in files) { "Invalid or oversized backup archive" }
                    require(entry.name == MANIFEST || entry.name == DATABASE || entry.name == EXCLUSIONS ||
                        isPreset(entry.name) || isAsset(entry.name)) { "Unsupported backup entry: ${entry.name}" }
                    target.parentFile!!.mkdirs()
                    target.outputStream().use { total += copyLimited(zip, it, MAX_ARCHIVE_BYTES - total) }
                    files[entry.name] = target
                }
                zip.closeEntry()
            }
        }
        return files
    }

    private fun validatePreset(name: String, text: String) {
        when (name.substringBefore('/')) {
            "presets" -> json.decodeFromString<List<Action>>(text)
            "vision_presets" -> json.decodeFromString<VisionPreset>(text).also { requireIdentity(name, it.id, it.name) }
            "flows" -> json.decodeFromString<FlowGraph>(text).also { requireIdentity(name, it.id, it.name) }
            "ml_presets" -> {
                val fields = json.parseToJsonElement(text).jsonObject
                require(fields["scope"] != null && fields["executionMode"] != null && fields["steps"] != null) { "Incomplete Screen ML preset" }
                com.autonion.automationcompanion.features.screen_understanding_ml.model.ScopeType.valueOf(fields.getValue("scope").jsonPrimitive.content)
                com.autonion.automationcompanion.features.screen_understanding_ml.model.ExecutionMode.valueOf(fields.getValue("executionMode").jsonPrimitive.content)
                fields.getValue("steps").jsonArray.forEach { step ->
                    val value = step.jsonObject
                    require(value["actionType"]?.jsonPrimitive?.content in com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType.entries.map { it.name }) { "Invalid Screen ML action" }
                    val anchor = value["anchor"]?.jsonObject ?: error("Missing Screen ML target")
                    require(anchor["bounds"] is kotlinx.serialization.json.JsonObject) { "Missing Screen ML target bounds" }
                }
                val preset = Gson().fromJson(text, AutomationPreset::class.java)
                requireIdentity(name, preset.id, preset.name)
            }
        }
        BackupImagePaths.collect(text)
    }

    private fun requireIdentity(path: String, id: String?, name: String?) {
        require(!id.isNullOrBlank() && !name.isNullOrBlank() && path.substringAfter('/').removeSuffix(".json") == id) {
            "Invalid preset identity: $path"
        }
    }
    private fun isPreset(name: String): Boolean = name.count { it == '/' } == 1 && name.endsWith(".json") &&
        name.substringBefore('/') in setOf("presets", "vision_presets", "ml_presets", "flows")
    private fun isAsset(name: String): Boolean = !isPreset(name) &&
        ((name.startsWith("vision_images/viz_") && name.count { it == '/' } == 1 && name.endsWith(".png")) ||
            name.startsWith("flow_assets/") ||
            (name.startsWith("ml_presets/") && name.count { it == '/' } >= 2))
    private fun resolveImage(path: String, entries: Set<String>): String? {
        if (path in entries) return path
        val normalized = path.replace('\\', '/')
        for (prefix in listOf("ml_presets/", "flow_assets/", "vision_images/")) {
            val index = normalized.lastIndexOf("/$prefix")
            if (index >= 0) normalized.substring(index + 1).let { if (it in entries) return it }
        }
        val vision = "vision_images/${normalized.substringAfterLast('/')}"
        return vision.takeIf { normalized.substringAfterLast('/').startsWith("viz_") && it in entries }
    }

    fun estimateBackupSize(features: List<String>): Long {
        var total = directories.filterKeys { it in features }.values.sumOf { dir ->
            File(context.filesDir, dir).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        if (BackupManifest.FEATURE_VISION_IMAGES in features) total += context.filesDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("viz_") && it.extension == "png" }.sumOf { it.length() }
        if (BackupManifest.FEATURE_SYSTEM_CONTEXT_DB in features) {
            val file = context.getDatabasePath("locauto.db")
            total += file.length() + File("${file.path}-wal").length()
        }
        return total
    }
    private fun workspace() = File(context.cacheDir, "backup_${UUID.randomUUID()}").apply { check(mkdirs()) }
    private fun readJson(file: File): String {
        require(file.length() <= 16L * 1024 * 1024) { "Backup metadata is too large" }
        return file.readText(Charsets.UTF_8)
    }
    private fun getAppVersion() = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    private fun sameBytes(a: File, b: File): Boolean {
        if (!b.isFile || a.length() != b.length()) return false
        a.inputStream().buffered().use { left -> b.inputStream().buffered().use { right ->
            val aBytes = ByteArray(32 * 1024)
            val bBytes = ByteArray(aBytes.size)
            val input = DataInputStream(right)
            while (true) {
                val count = left.read(aBytes)
                if (count == -1) return right.read() == -1
                input.readFully(bBytes, 0, count)
                for (index in 0 until count) if (aBytes[index] != bBytes[index]) return false
            }
        } }
    }
    private fun inside(root: File, file: File) = file.canonicalPath.startsWith(root.canonicalPath + File.separator)
    private fun safeFile(root: File, name: String): File {
        require(name.isNotBlank() && !name.contains('\\') && !name.startsWith('/') && ':' !in name &&
            name.trimEnd('/').split('/').none { it.isEmpty() || it == ".." || it == "." }) { "Invalid backup path" }
        return File(root, name).also { require(inside(root, it)) { "Backup entry escapes its destination" } }
    }
    private fun copyLimited(input: InputStream, output: OutputStream, limit: Long): Long {
        var count = 0L
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) return count
            count += read
            require(count <= limit) { "Backup exceeds the 2 GB limit" }
            output.write(buffer, 0, read)
        }
    }
    companion object {
        private val operationLock = Any()
        private const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024
    }
}
