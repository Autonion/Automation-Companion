package com.autonion.automationcompanion.core.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.autonion.automationcompanion.core.settings.ExclusionManager
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "BackupManager"

// Archive entry paths
private const val MANIFEST_ENTRY = "manifest.json"
private const val GESTURE_PRESETS_PREFIX = "presets/"
private const val VISION_PRESETS_PREFIX = "vision_presets/"
private const val VISION_IMAGES_PREFIX = "vision_images/"
private const val ML_PRESETS_PREFIX = "ml_presets/"
private const val FLOWS_PREFIX = "flows/"
private const val FLOW_ASSETS_PREFIX = "flow_assets/"
private const val DATABASE_ENTRY = "database/locauto.db"
private const val EXCLUSION_ENTRY = "preferences/exclusion_list.json"

// Room database name
private const val ROOM_DB_NAME = "locauto.db"

/**
 * Orchestrates full-app backup and restore.
 *
 * **Export** collects all user data directories, the Room database, and
 * SharedPreferences into a single ZIP archive. Optionally encrypts the
 * archive with AES-256-GCM using the provided password.
 *
 * **Import** reads the archive (decrypting if needed), validates the
 * manifest, and restores all data to the correct locations.
 */
class BackupManager(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ─── Data directories ───────────────────────────────────────────────

    private val gesturePresetsDir get() = File(context.filesDir, "presets")
    private val visionPresetsDir get() = File(context.filesDir, "vision_presets")
    private val mlPresetsDir get() = File(context.filesDir, "ml_presets")
    private val flowsDir get() = File(context.filesDir, "flows")
    private val flowAssetsDir get() = File(context.filesDir, "flow_assets")

    // ═══════════════════════════════════════════════════════════════════
    // EXPORT
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Exports all selected features to a `.atnbak` file at [uri].
     *
     * @param uri          SAF-provided content URI to write to.
     * @param features     List of [BackupManifest] feature constants to include.
     * @param password     If non-null/non-blank, the archive is AES-256 encrypted.
     * @param onProgress   Progress callback (0–100).
     * @return true if successful.
     */
    fun export(
        uri: Uri,
        features: List<String>,
        password: String?,
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        return try {
            onProgress(0)

            // 1. Build the ZIP in memory
            val zipBytes = ByteArrayOutputStream()
            ZipOutputStream(zipBytes).use { zip ->
                var step = 0
                val totalSteps = features.size + 1 // +1 for manifest

                // Gesture presets
                if (BackupManifest.FEATURE_GESTURE_PRESETS in features) {
                    packDirectory(zip, gesturePresetsDir, GESTURE_PRESETS_PREFIX)
                    step++
                    onProgress((step * 80) / totalSteps)
                }

                // Vision presets (JSON)
                if (BackupManifest.FEATURE_VISION_PRESETS in features) {
                    packDirectory(zip, visionPresetsDir, VISION_PRESETS_PREFIX)
                    step++
                    onProgress((step * 80) / totalSteps)
                }

                // Vision images (viz_*.png in filesDir root)
                if (BackupManifest.FEATURE_VISION_IMAGES in features) {
                    packVisionImages(zip)
                    step++
                    onProgress((step * 80) / totalSteps)
                }

                // Screen ML presets
                if (BackupManifest.FEATURE_ML_PRESETS in features) {
                    packDirectory(zip, mlPresetsDir, ML_PRESETS_PREFIX)
                    step++
                    onProgress((step * 80) / totalSteps)
                }

                // Flow graphs
                if (BackupManifest.FEATURE_FLOWS in features) {
                    packDirectory(zip, flowsDir, FLOWS_PREFIX)
                    step++
                    onProgress((step * 80) / totalSteps)
                }

                // Flow assets (nested subdirectories)
                if (BackupManifest.FEATURE_FLOW_ASSETS in features) {
                    packDirectoryRecursive(zip, flowAssetsDir, FLOW_ASSETS_PREFIX)
                    step++
                    onProgress((step * 80) / totalSteps)
                }

                // Room database — close DB first, copy file
                if (BackupManifest.FEATURE_SYSTEM_CONTEXT_DB in features) {
                    packDatabase(zip)
                    step++
                    onProgress((step * 80) / totalSteps)
                }

                // Excluded apps (SharedPreferences → JSON)
                if (BackupManifest.FEATURE_EXCLUDED_APPS in features) {
                    packExclusionList(zip)
                    step++
                    onProgress((step * 80) / totalSteps)
                }

                // Write manifest
                val manifest = BackupManifest(
                    appVersion = getAppVersion(),
                    backupTimestamp = System.currentTimeMillis(),
                    isEncrypted = !password.isNullOrBlank(),
                    includedFeatures = features
                )
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(json.encodeToString(manifest).toByteArray())
                zip.closeEntry()
            }

            onProgress(85)

            // 2. Write to output — encrypt if password is set
            val rawZip = zipBytes.toByteArray()
            context.contentResolver.openOutputStream(uri)?.use { outStream ->
                if (!password.isNullOrBlank()) {
                    Log.d(TAG, "Encrypting backup with AES-256-GCM...")
                    CryptoUtils.encrypt(ByteArrayInputStream(rawZip), outStream, password)
                } else {
                    outStream.write(rawZip)
                }
            }

            onProgress(100)
            Log.d(TAG, "Backup exported successfully (${rawZip.size} bytes, encrypted=${!password.isNullOrBlank()})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // IMPORT
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Result of an import operation.
     */
    sealed class ImportResult {
        data class Success(val manifest: BackupManifest) : ImportResult()
        data class NeedsPassword(val dummy: Unit = Unit) : ImportResult()
        data class WrongPassword(val message: String) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    /**
     * Imports a backup from [uri].
     *
     * @param uri       SAF-provided content URI to read from.
     * @param password  Password for decryption (null if not encrypted).
     * @param onProgress Progress callback (0–100).
     * @return [ImportResult] describing the outcome.
     */
    fun import(
        uri: Uri,
        password: String?,
        onProgress: (Int) -> Unit = {}
    ): ImportResult {
        return try {
            onProgress(0)

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ImportResult.Error("Cannot open backup file")
            val buffered = BufferedInputStream(inputStream)

            // Peek at header to detect encryption
            buffered.mark(8)
            val header = ByteArray(4)
            val bytesRead = buffered.read(header)
            buffered.reset()

            val zipBytes: ByteArray

            if (bytesRead >= 4 && CryptoUtils.isEncrypted(header)) {
                // File is encrypted
                if (password.isNullOrBlank()) {
                    buffered.close()
                    return ImportResult.NeedsPassword()
                }

                try {
                    val decrypted = ByteArrayOutputStream()
                    CryptoUtils.decrypt(buffered, decrypted, password)
                    zipBytes = decrypted.toByteArray()
                } catch (e: WrongPasswordException) {
                    return ImportResult.WrongPassword(e.message ?: "Incorrect password")
                } catch (e: InvalidBackupException) {
                    return ImportResult.Error(e.message ?: "Invalid backup file")
                }
            } else {
                // Plain ZIP
                zipBytes = buffered.readBytes()
            }

            buffered.close()
            onProgress(20)

            // Parse the ZIP archive
            var manifest: BackupManifest? = null
            val entries = mutableListOf<Pair<String, ByteArray>>()

            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val data = zip.readBytes()
                        if (entry.name == MANIFEST_ENTRY) {
                            manifest = json.decodeFromString<BackupManifest>(String(data))
                        } else {
                            entries.add(entry.name to data)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            if (manifest == null) {
                return ImportResult.Error("Invalid backup: missing manifest")
            }

            onProgress(40)
            Log.d(TAG, "Importing backup: version=${manifest!!.appVersion}, features=${manifest!!.includedFeatures}")

            // Restore each data set
            val totalEntries = entries.size.coerceAtLeast(1)
            entries.forEachIndexed { index, (name, data) ->
                restoreEntry(name, data)
                onProgress(40 + ((index + 1) * 55) / totalEntries)
            }

            onProgress(100)
            Log.d(TAG, "Import completed successfully")
            ImportResult.Success(manifest!!)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            ImportResult.Error(e.message ?: "Unknown error during import")
        }
    }

    // ─── Pack helpers ────────────────────────────────────────────────────

    /** Pack all files in a directory (non-recursive) into the ZIP under [prefix]. */
    private fun packDirectory(zip: ZipOutputStream, dir: File, prefix: String) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles()?.forEach { file ->
            if (file.isFile) {
                zip.putNextEntry(ZipEntry("$prefix${file.name}"))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
                Log.d(TAG, "Packed: $prefix${file.name} (${file.length()} bytes)")
            }
        }
    }

    /** Pack all files recursively under [dir] into the ZIP under [prefix]. */
    private fun packDirectoryRecursive(zip: ZipOutputStream, dir: File, prefix: String) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relativePath = file.relativeTo(dir).path.replace('\\', '/')
            zip.putNextEntry(ZipEntry("$prefix$relativePath"))
            FileInputStream(file).use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /** Pack vision template/capture images (viz_*.png) from filesDir root. */
    private fun packVisionImages(zip: ZipOutputStream) {
        context.filesDir.listFiles()?.filter { file ->
            file.isFile && file.name.startsWith("viz_") && file.name.endsWith(".png")
        }?.forEach { file ->
            zip.putNextEntry(ZipEntry("$VISION_IMAGES_PREFIX${file.name}"))
            FileInputStream(file).use { it.copyTo(zip) }
            zip.closeEntry()
            Log.d(TAG, "Packed vision image: ${file.name} (${file.length()} bytes)")
        }
    }

    /** Checkpoint the Room DB and pack it. */
    private fun packDatabase(zip: ZipOutputStream) {
        // Force a WAL checkpoint so all data is in the main DB file
        try {
            val db = AppDatabase.get(context)
            db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
        } catch (e: Exception) {
            Log.w(TAG, "WAL checkpoint failed (non-fatal)", e)
        }

        val dbFile = context.getDatabasePath(ROOM_DB_NAME)
        if (dbFile.exists()) {
            zip.putNextEntry(ZipEntry(DATABASE_ENTRY))
            FileInputStream(dbFile).use { it.copyTo(zip) }
            zip.closeEntry()
            Log.d(TAG, "Packed database: ${dbFile.length()} bytes")
        }
    }

    /** Serialize the exclusion list to JSON and pack it. */
    private fun packExclusionList(zip: ZipOutputStream) {
        val exclusionJson = ExclusionManager.exportToJson()
        zip.putNextEntry(ZipEntry(EXCLUSION_ENTRY))
        zip.write(exclusionJson.toByteArray())
        zip.closeEntry()
        Log.d(TAG, "Packed exclusion list")
    }

    // ─── Restore helpers ────────────────────────────────────────────────

    /** Route an extracted entry to the correct restore location. */
    private fun restoreEntry(name: String, data: ByteArray) {
        when {
            name.startsWith(GESTURE_PRESETS_PREFIX) -> {
                restoreToDir(gesturePresetsDir, name.removePrefix(GESTURE_PRESETS_PREFIX), data)
            }
            name.startsWith(VISION_PRESETS_PREFIX) -> {
                restoreToDir(visionPresetsDir, name.removePrefix(VISION_PRESETS_PREFIX), data)
            }
            name.startsWith(VISION_IMAGES_PREFIX) -> {
                val fileName = name.removePrefix(VISION_IMAGES_PREFIX)
                val file = File(context.filesDir, fileName)
                file.writeBytes(data)
                Log.d(TAG, "Restored vision image: $fileName")
            }
            name.startsWith(ML_PRESETS_PREFIX) -> {
                restoreToDir(mlPresetsDir, name.removePrefix(ML_PRESETS_PREFIX), data)
            }
            name.startsWith(FLOWS_PREFIX) -> {
                restoreToDir(flowsDir, name.removePrefix(FLOWS_PREFIX), data)
            }
            name.startsWith(FLOW_ASSETS_PREFIX) -> {
                val relativePath = name.removePrefix(FLOW_ASSETS_PREFIX)
                val file = File(flowAssetsDir, relativePath)
                file.parentFile?.mkdirs()
                file.writeBytes(data)
                Log.d(TAG, "Restored flow asset: $relativePath")
            }
            name == DATABASE_ENTRY -> {
                restoreDatabase(data)
            }
            name == EXCLUSION_ENTRY -> {
                ExclusionManager.importFromJson(context, String(data))
                Log.d(TAG, "Restored exclusion list")
            }
            else -> {
                Log.w(TAG, "Unknown archive entry, skipping: $name")
            }
        }
    }

    /** Write data to a file inside [dir], creating the directory if needed. */
    private fun restoreToDir(dir: File, fileName: String, data: ByteArray) {
        if (fileName.isBlank()) return
        dir.mkdirs()
        val file = File(dir, fileName)
        file.writeBytes(data)
        Log.d(TAG, "Restored: ${dir.name}/$fileName (${data.size} bytes)")
    }

    /** Restore the Room database file, closing the current connection first. */
    private fun restoreDatabase(data: ByteArray) {
        try {
            // Close the existing database connection
            AppDatabase.get(context).close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close DB before restore (non-fatal)", e)
        }

        val dbFile = context.getDatabasePath(ROOM_DB_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes(data)

        // Also remove WAL and SHM files to avoid stale state
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()

        Log.d(TAG, "Restored database: ${data.size} bytes")
    }

    // ─── Utility ────────────────────────────────────────────────────────

    private fun getAppVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Estimates the total backup size in bytes for the selected features.
     * This is a rough estimate for UI display purposes.
     */
    fun estimateBackupSize(features: List<String>): Long {
        var total = 0L

        if (BackupManifest.FEATURE_GESTURE_PRESETS in features) {
            total += dirSize(gesturePresetsDir)
        }
        if (BackupManifest.FEATURE_VISION_PRESETS in features) {
            total += dirSize(visionPresetsDir)
        }
        if (BackupManifest.FEATURE_VISION_IMAGES in features) {
            context.filesDir.listFiles()?.filter {
                it.isFile && it.name.startsWith("viz_") && it.name.endsWith(".png")
            }?.forEach { total += it.length() }
        }
        if (BackupManifest.FEATURE_ML_PRESETS in features) {
            total += dirSize(mlPresetsDir)
        }
        if (BackupManifest.FEATURE_FLOWS in features) {
            total += dirSize(flowsDir)
        }
        if (BackupManifest.FEATURE_FLOW_ASSETS in features) {
            total += dirSizeRecursive(flowAssetsDir)
        }
        if (BackupManifest.FEATURE_SYSTEM_CONTEXT_DB in features) {
            val dbFile = context.getDatabasePath(ROOM_DB_NAME)
            if (dbFile.exists()) total += dbFile.length()
        }

        return total
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0
    }

    private fun dirSizeRecursive(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
