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

class ModelStorageManager(private val context: Context) {
    private val TAG = "ModelStorageManager"
    private val prefs = context.getSharedPreferences("AutonionModelSettings", Context.MODE_PRIVATE)
    
    // Directory where imported models are securely fully contained
    private val modelsDir: File = File(context.filesDir, "models")

    init {
        if (!modelsDir.exists()) modelsDir.mkdirs()
    }

    /**
     * Copies a heavy SLM `.bin` file selected by the user into internal storage
     */
    suspend fun importModelFromUri(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        try {
            val fileName = getFileName(uri) ?: "imported_model_${System.currentTimeMillis()}.bin"
            val destFile = File(modelsDir, fileName)

            Log.d(TAG, "Importing heavy model $fileName from URI: $uri")

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(destFile).use { outputStream ->
                    copyStreamWithProgress(inputStream, outputStream)
                }
            } ?: return@withContext Result.failure(Exception("Unable to open input stream for URI"))

            // Automatically set as active if it's the only one
            if (getActiveModelPath() == null) {
                setActiveModelPath(destFile.absolutePath)
            }

            Log.d(TAG, "Import successful: ${destFile.absolutePath}")
            Result.success(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import model", e)
            Result.failure(e)
        }
    }

    fun getImportedModels(): List<File> {
        return modelsDir.listFiles { _, name -> name.endsWith(".bin") }?.toList() ?: emptyList()
    }

    fun getActiveModelPath(): String? {
        val path = prefs.getString("ACTIVE_MODEL_PATH", null)
        return if (path != null && File(path).exists()) path else null
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
        val buffer = ByteArray(8192) // 8KB chunks
        var bytesRead: Int
        var totalRead = 0L
        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
            totalRead += bytesRead
            // Progress could be emitted here using Flow for the UI later!
        }
        output.flush()
    }
}
