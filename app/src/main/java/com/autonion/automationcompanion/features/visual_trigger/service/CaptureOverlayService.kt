package com.autonion.automationcompanion.features.visual_trigger.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.autonion.automationcompanion.R
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract
import com.autonion.automationcompanion.features.visual_trigger.ui.VisionEditorActivity
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class CaptureOverlayService : Service() {

    companion object {
        private const val TAG = "CaptureOverlay"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    private var resultCode: Int = 0
    private var resultData: Intent? = null
    private var presetName: String = "New Automation"

    // Flow mode state
    private var isFlowMode = false
    private var flowNodeId: String? = null
    private var flowVisionJson: String? = null
    private var clearOnStart: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_START_OVERLAY" -> {
                resultCode = intent.getIntExtra("EXTRA_RESULT_CODE", 0)
                resultData = intent.getParcelableExtra("EXTRA_RESULT_DATA")
                presetName = intent.getStringExtra("EXTRA_PRESET_NAME") ?: "New Automation"
                
                if (intent.getBooleanExtra(FlowOverlayContract.EXTRA_FLOW_MODE, false)) {
                    isFlowMode = true
                    flowNodeId = intent.getStringExtra(FlowOverlayContract.EXTRA_FLOW_NODE_ID)
                    intent.getStringExtra("EXTRA_FLOW_VISION_JSON")?.let { flowVisionJson = it }
                    intent.getBooleanExtra("EXTRA_CLEAR_ON_START", false).let { if (it) clearOnStart = true }
                }
                
                startForegroundServiceNotification()
                showOverlay()
            }
            "ACTION_SHOW_OVERLAY" -> {
                // Called after editor finishes — re-show the overlay (not used much in flow mode)
                overlayView?.visibility = View.VISIBLE
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "vision_capture_channel"
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                channelId,
                "Vision Capture",
                NotificationManager.IMPORTANCE_LOW
            )
        } else {
            TODO("VERSION.SDK_INT < O")
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Vision Capture Active")
            .setContentText("Use the overlay to capture a screenshot")
            .setSmallIcon(com.autonion.automationcompanion.R.drawable.ic_notification)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1001, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlayView != null) return

        // Check overlay permission before attempting to add window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay permission not granted — cannot show capture overlay")
            DebugLogger.error(applicationContext, LogCategory.VISUAL_TRIGGER, "Overlay Permission Denied", "Cannot show capture overlay without permission", TAG)
            // Prompt user to grant permission
            try {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open overlay permission settings", e)
            }
            stopSelf()
            return
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - 200
            y = resources.displayMetrics.heightPixels / 3
        }
        overlayLayoutParams = lp

        val dp = resources.displayMetrics.density

        // Container — dark pill
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28 * dp
                setColor(0xE61A1A2E.toInt())
                setStroke((1.5f * dp).toInt(), 0x40FFFFFF)
            }
            elevation = 8 * dp
        }

        // Capture button (green pill)
        val captureBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * dp
                setColor(0xFF00C853.toInt())
            }
            isClickable = true
            isFocusable = true

            val cameraIcon = ImageView(this@CaptureOverlayService).apply {
                setImageResource(android.R.drawable.ic_menu_camera)
                setColorFilter(0xFFFFFFFF.toInt())
            }
            cameraIcon.layoutParams = LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt())
            addView(cameraIcon)

            val label = TextView(this@CaptureOverlayService).apply {
                text = "Capture"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                setPadding((6 * dp).toInt(), 0, 0, 0)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            addView(label)

            setOnClickListener { captureScreen() }
        }

        // Spacer
        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams((12 * dp).toInt(), 1)

        // Cancel (X) button — stops everything
        val cancelBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(0xBBFFFFFF.toInt())
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
            setOnClickListener { stopSelf() }
        }
        cancelBtn.layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())

        container.addView(captureBtn)
        container.addView(spacer)
        container.addView(cancelBtn)

        // Draggable
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) > 5 || abs(dy) > 5) isDragging = true
                    if (isDragging) {
                        lp.x = initialX + dx.toInt()
                        lp.y = initialY + dy.toInt()
                        windowManager?.updateViewLayout(container, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> isDragging
                else -> false
            }
        }

        overlayView = container
        try {
            windowManager?.addView(overlayView, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view — likely missing overlay permission", e)
            overlayView = null
            stopSelf()
        }
    }

    private fun captureScreen() {
        if (resultData == null) return

        // Hide overlay before capture
        overlayView?.visibility = View.GONE

        // Wait for UI to settle (500ms)
        Handler(Looper.getMainLooper()).postDelayed({
            startProjection()
        }, 500)
    }

    private fun startProjection() {
        try {
            // Reuse existing projection if already active
            if (mediaProjection == null) {
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)
                Log.d(TAG, "Created new MediaProjection")
                DebugLogger.info(applicationContext, LogCategory.VISUAL_TRIGGER, "Projection Created", "New MediaProjection created for screen capture", TAG)
            }

            // Android 14+: must register callback before EVERY createVirtualDisplay call
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection onStop callback")
                    cleanupProjection()
                }
            }, Handler(Looper.getMainLooper()))

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            if (virtualDisplay == null) {
                Log.e(TAG, "Failed to create virtual display")
                overlayView?.visibility = View.VISIBLE
                return
            }

            Log.d(TAG, "Virtual display created, waiting for frame...")

            // Use delayed polling to grab the frame (more reliable than listener)
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed(object : Runnable {
                var retries = 0
                override fun run() {
                    val reader = imageReader ?: return
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        Log.d(TAG, "Frame acquired on attempt $retries")
                        DebugLogger.success(applicationContext, LogCategory.VISUAL_TRIGGER, "Frame Captured", "Screen frame acquired on attempt $retries", TAG)
                        try {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * width

                            val bitmap = Bitmap.createBitmap(
                                width + rowPadding / pixelStride,
                                height,
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.copyPixelsFromBuffer(buffer)

                            val finalBitmap = if (rowPadding == 0) bitmap
                            else Bitmap.createBitmap(bitmap, 0, 0, width, height)

                            image.close()

                            // Release virtual display but keep projection alive
                            virtualDisplay?.release()
                            virtualDisplay = null
                            imageReader?.close()
                            imageReader = null

                            saveAndOpenEditor(finalBitmap)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing frame", e)
                            DebugLogger.error(applicationContext, LogCategory.VISUAL_TRIGGER, "Frame Error", "Error processing captured frame: ${e.message}", TAG)
                            image.close()
                            overlayView?.visibility = View.VISIBLE
                        }
                    } else {
                        retries++
                        if (retries < 10) {
                            Log.d(TAG, "No frame yet, retry $retries...")
                            handler.postDelayed(this, 200)
                        } else {
                            Log.e(TAG, "Failed to acquire frame after $retries retries")
                            overlayView?.visibility = View.VISIBLE
                        }
                    }
                }
            }, 300)
        } catch (e: Exception) {
            Log.e(TAG, "Error in startProjection", e)
            DebugLogger.error(applicationContext, LogCategory.VISUAL_TRIGGER, "Projection Error", "Error starting projection: ${e.message}", TAG)
            overlayView?.visibility = View.VISIBLE
        }
    }

    private fun saveAndOpenEditor(bitmap: Bitmap) {
        try {
            val file = File(cacheDir, "capture_temp.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val intent = Intent(this, VisionEditorActivity::class.java).apply {
                putExtra("IMAGE_PATH", file.absolutePath)
                putExtra("EXTRA_PRESET_NAME", presetName)
                if (isFlowMode) {
                    putExtra(FlowOverlayContract.EXTRA_FLOW_MODE, true)
                    putExtra(FlowOverlayContract.EXTRA_FLOW_NODE_ID, flowNodeId)
                    flowVisionJson?.let { putExtra("EXTRA_FLOW_VISION_JSON", it) }
                    if (clearOnStart) putExtra("EXTRA_CLEAR_ON_START", true)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

            // DON'T stopSelf() — keep service alive, overlay will re-show on ACTION_SHOW_OVERLAY
            // In FLOW_MODE, the EditorActivity will handle stopping the service when done via broadcast
        } catch (e: Exception) {
            Log.e(TAG, "Error saving capture", e)
            DebugLogger.error(applicationContext, LogCategory.VISUAL_TRIGGER, "Save Error", "Error saving capture: ${e.message}", TAG)
        }
    }

    private fun cleanupProjection() {
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        mediaProjection = null
        imageReader = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (overlayView != null) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            overlayView = null
        }
        mediaProjection?.stop()
        cleanupProjection()
    }
}
