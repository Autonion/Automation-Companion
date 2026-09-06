package com.autonion.automationcompanion.features.semantic_automation.ml

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Supported on-device SLM model formats.
 * MEDIAPIPE: .bin / .task files loaded via MediaPipe LlmInference
 * GGUF:     .gguf files loaded via llama.cpp
 */
enum class ModelFormat {
    MEDIAPIPE,
    GGUF
}

class ModelStorageManager(private val context: Context) {
    private val TAG = "ModelStorageManager"
    private val prefs = context.getSharedPreferences("AutonionModelSettings", Context.MODE_PRIVATE)
    
    // Directory where imported models are securely fully contained
    private val modelsDir: File = File(context.filesDir, "models")

    init {
        if (!modelsDir.exists()) modelsDir.mkdirs()
        // Clean up any non-model files (like video/audio/temp files) that may have been imported accidentally
        modelsDir.listFiles()?.forEach { file ->
            val n = file.name.lowercase()
            if (!n.endsWith(".gguf") && !n.endsWith(".bin") && !n.endsWith(".task") && !n.endsWith(".tflite") && !n.endsWith(".onnx")) {
                Log.w(TAG, "Cleaning up non-model file from models directory: ${file.name}")
                if (prefs.getString("ACTIVE_MODEL_PATH", null) == file.absolutePath) {
                    setActiveModelPath(null)
                }
                file.delete()
            }
        }
    }

    /**
     * Copies a heavy SLM `.gguf` / `.bin` / `.task` file selected by the user into internal storage
     */
    suspend fun importModelFromUri(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        try {
            val fileName = getFileName(uri) ?: "imported_model_${System.currentTimeMillis()}.gguf"
            val lowerName = fileName.lowercase()
            val isModelExt = lowerName.endsWith(".gguf") || lowerName.endsWith(".bin") || lowerName.endsWith(".task") || lowerName.endsWith(".tflite")
            if (!isModelExt) {
                return@withContext Result.failure(
                    IllegalArgumentException("Invalid model file ($fileName). Please select a .gguf, .bin, or .task file.")
                )
            }

            val destFile = File(modelsDir, fileName)

            Log.d(TAG, "Importing heavy model $fileName from URI: $uri")

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(destFile).use { outputStream ->
                    copyStreamWithProgress(inputStream, outputStream)
                }
            } ?: return@withContext Result.failure(Exception("Unable to open input stream for URI"))

            // Automatically set the newly imported model as active
            setActiveModelPath(destFile.absolutePath)

            Log.d(TAG, "Import successful: ${destFile.absolutePath}")
            Result.success(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import model", e)
            Result.failure(e)
        }
    }

    fun getImportedModels(): List<File> {
        return modelsDir.listFiles { _, name ->
            val lower = name.lowercase()
            lower.endsWith(".bin") || lower.endsWith(".task") || lower.endsWith(".gguf") || lower.endsWith(".tflite")
        }?.toList() ?: emptyList()
    }

    /**
     * Determines the model format from file header magic bytes and extension.
     * Supports both .gguf files and GGUF models saved with .bin extension.
     */
    fun getModelFormat(file: File): ModelFormat {
        if (!file.exists() || file.length() < 4) {
            return if (file.name.endsWith(".gguf")) ModelFormat.GGUF else ModelFormat.MEDIAPIPE
        }
        try {
            java.io.FileInputStream(file).use { fis ->
                val header = ByteArray(4)
                if (fis.read(header) == 4) {
                    // GGUF magic header ('G' 'G' 'U' 'F' = 0x47 0x47 0x55 0x46)
                    if (header[0] == 0x47.toByte() &&
                        header[1] == 0x47.toByte() &&
                        header[2] == 0x55.toByte() &&
                        header[3] == 0x46.toByte()
                    ) {
                        return ModelFormat.GGUF
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not inspect file header for format detection", e)
        }
        return if (file.name.endsWith(".gguf")) ModelFormat.GGUF else ModelFormat.MEDIAPIPE
    }

    /**
     * Returns the format of the currently active model, or null if none is set.
     */
    fun getActiveModelFormat(): ModelFormat? {
        val path = getActiveModelPath() ?: return null
        return getModelFormat(File(path))
    }

    fun getActiveModelPath(): String? {
        val path = prefs.getString("ACTIVE_MODEL_PATH", null)
        if (path != null) {
            val file = File(path)
            val n = file.name.lowercase()
            if (file.exists() && (n.endsWith(".gguf") || n.endsWith(".bin") || n.endsWith(".task") || n.endsWith(".tflite"))) {
                return path
            }
        }
        // If stored active path is invalid/deleted/non-model, fallback to first valid imported model
        val imported = getImportedModels()
        if (imported.isNotEmpty()) {
            val first = imported.first().absolutePath
            setActiveModelPath(first)
            return first
        }
        return null
    }

    fun setActiveModelPath(absolutePath: String?) {
        prefs.edit().putString("ACTIVE_MODEL_PATH", absolutePath).apply()
    }
    
    fun removeModel(file: File): Boolean {
        if (getActiveModelPath() == file.absolutePath) {
            setActiveModelPath(null)
        }
        return file.delete()
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }

    private fun copyStreamWithProgress(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(65536) // 64KB buffer for fast transfer of large models
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
        }
        output.flush()
    }
}
