package com.autonion.automationcompanion.features.visual_trigger.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
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
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.autonion.automationcompanion.R
import com.autonion.automationcompanion.core.ui.OverlayStyles
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract
import com.autonion.automationcompanion.features.visual_trigger.ui.VisionEditorActivity
import java.io.File
import java.io.FileOutputStream

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

    private var activePresetId: String? = null
    private var doneBtn: View? = null
    private var doneSpacer: View? = null

    /** Continuously updated with the latest screen frame (like ScreenML's latestBitmap). */
    @Volatile
    private var latestBitmap: Bitmap? = null

    /** True when the service is being stopped intentionally, to avoid the "projection lost" toast. */
    @Volatile
    private var stoppedByUser = false

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
                resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("EXTRA_RESULT_DATA", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("EXTRA_RESULT_DATA")
                }
                presetName = intent.getStringExtra("EXTRA_PRESET_NAME") ?: "New Automation"
                
                if (intent.getBooleanExtra(FlowOverlayContract.EXTRA_FLOW_MODE, false)) {
                    isFlowMode = true
                    flowNodeId = intent.getStringExtra(FlowOverlayContract.EXTRA_FLOW_NODE_ID)
                    intent.getStringExtra("EXTRA_FLOW_VISION_JSON")?.let { flowVisionJson = it }
                    intent.getBooleanExtra("EXTRA_CLEAR_ON_START", false).let { if (it) clearOnStart = true }
                }
                
                startForegroundServiceNotification()
                // Start projection immediately so frames start caching
                startProjection()
                showOverlay()
            }
            "ACTION_SHOW_OVERLAY" -> {
                // Called after editor finishes — re-show the overlay (not used much in flow mode)
                intent?.getStringExtra("EXTRA_PRESET_ID")?.let {
                    activePresetId = it
                    doneBtn?.visibility = View.VISIBLE
                    doneSpacer?.visibility = View.VISIBLE
                }
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

    /**
     * Start the MediaProjection and VirtualDisplay with the surface always connected,
     * continuously caching the latest frame into [latestBitmap].
     * This matches the pattern used by ScreenUnderstandingService / MediaProjectionCore.
     */
    private fun startProjection() {
        if (resultData == null || mediaProjection != null) return

        try {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)
            Log.d(TAG, "Created new MediaProjection")
            DebugLogger.info(applicationContext, LogCategory.VISUAL_TRIGGER, "Projection Created", "New MediaProjection created for screen capture", TAG)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection onStop callback")
                    cleanupProjection()
                    // Only notify user if projection was revoked externally
                    if (!stoppedByUser) {
                        Log.w(TAG, "MediaProjection lost externally — stopping service")
                        DebugLogger.warning(applicationContext, LogCategory.VISUAL_TRIGGER,
                            "Screen capture lost",
                            "MediaProjection revoked by the system — restart required", TAG)
                        Handler(Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(this@CaptureOverlayService,
                                "Screen capture lost — please restart", android.widget.Toast.LENGTH_LONG).show()
                        }
                        stopSelf()
                    }
                }
            }, Handler(Looper.getMainLooper()))

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            // Continuously cache the latest frame — same pattern as MediaProjectionCore
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
                if (image != null) {
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

                        // Replace cached bitmap (recycle old one)
                        val old = latestBitmap
                        latestBitmap = finalBitmap
                        old?.recycle()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing frame", e)
                    } finally {
                        image.close()
                    }
                }
            }, Handler(Looper.getMainLooper()))

            // Create VirtualDisplay with surface CONNECTED (not null) so frames flow immediately
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
            Log.d(TAG, "VirtualDisplay created with surface connected — frames will cache continuously")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting projection", e)
            DebugLogger.error(applicationContext, LogCategory.VISUAL_TRIGGER, "Projection Error", "Error starting projection: ${e.message}", TAG)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlayView != null) return

        // Check overlay permission before attempting to add window
        if (!OverlayStyles.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay permission not granted — cannot show capture overlay")
            DebugLogger.error(applicationContext, LogCategory.VISUAL_TRIGGER, "Overlay Permission Denied", "Cannot show capture overlay without permission", TAG)
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

        val lp = OverlayStyles.createOverlayLayoutParams(this)
        overlayLayoutParams = lp

        val dp = resources.displayMetrics.density

        // Container — unified dark pill
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (OverlayStyles.PANEL_PADDING_H_DP * dp).toInt(),
                (OverlayStyles.PANEL_PADDING_V_DP * dp).toInt(),
                (OverlayStyles.PANEL_PADDING_H_DP * dp).toInt(),
                (OverlayStyles.PANEL_PADDING_V_DP * dp).toInt()
            )
            background = OverlayStyles.createPanelBackground(dp)
            elevation = OverlayStyles.PANEL_ELEVATION_DP * dp
        }

        // Capture button (icon only, matching other overlays)
        val captureBtn = OverlayStyles.createIconButton(
            context = this,
            iconRes = android.R.drawable.ic_menu_camera,
            contentDescription = "Capture"
        ) { captureScreen() }

        // Spacer
        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(
            (OverlayStyles.BUTTON_SPACING_DP * dp).toInt(), 1
        )

        // Done button (hidden until at least one capture is saved)
        doneSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams((OverlayStyles.BUTTON_SPACING_DP * dp).toInt(), 1)
            visibility = if (activePresetId != null) View.VISIBLE else View.GONE
        }
        var doneBtnRef: android.widget.ImageView? = null
        doneBtn = OverlayStyles.createIconButton(
            context = this,
            iconRes = android.R.drawable.ic_menu_save,
            contentDescription = "Done"
        ) {
            // Animate save confirmation on the button itself
            val btn = doneBtnRef
            if (btn != null) {
                btn.setImageResource(com.autonion.automationcompanion.R.drawable.ic_success)
                btn.setColorFilter(android.graphics.Color.GREEN)
                btn.animate()
                    .scaleX(1.3f).scaleY(1.3f)
                    .setDuration(150)
                    .withEndAction {
                        btn.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                    }.start()

                // Revert after 2 seconds
                btn.postDelayed({
                    btn.setImageResource(android.R.drawable.ic_menu_save)
                    btn.setColorFilter(OverlayStyles.ICON_TINT_NORMAL)
                }, 2000)
            }
        }.apply {
            visibility = if (activePresetId != null) View.VISIBLE else View.GONE
        }
        doneBtnRef = doneBtn as? android.widget.ImageView

        // Cancel (X) button
        val cancelBtn = OverlayStyles.createIconButton(
            context = this,
            iconRes = android.R.drawable.ic_menu_close_clear_cancel,
            contentDescription = "Cancel"
        ) { stopSelf() }

        container.addView(captureBtn)
        container.addView(doneSpacer)
        container.addView(doneBtn)
        container.addView(spacer)
        container.addView(cancelBtn)

        // Draggable
        OverlayStyles.attachDragBehavior(container, lp, windowManager!!)

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
        // Hide overlay before capture so it doesn't appear in the screenshot
        overlayView?.visibility = View.GONE

        // Wait briefly for the overlay to fully disappear, then grab the cached frame
        Handler(Looper.getMainLooper()).postDelayed({
            // The latestBitmap may still show the overlay since we just hid it.
            // Wait one more frame cycle for a clean frame without the overlay.
            Handler(Looper.getMainLooper()).postDelayed({
                val bitmap = latestBitmap
                if (bitmap != null) {
                    val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                    saveAndOpenEditor(copy)
                } else {
                    Log.w(TAG, "No cached frame available yet")
                    android.widget.Toast.makeText(this, "No frame captured yet, try again...", android.widget.Toast.LENGTH_SHORT).show()
                    overlayView?.visibility = View.VISIBLE
                }
            }, 300)
        }, 200)
    }

    private fun saveAndOpenEditor(bitmap: Bitmap) {
        try {
            val file = File(cacheDir, "capture_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val intent = Intent(this, VisionEditorActivity::class.java).apply {
                putExtra("IMAGE_PATH", file.absolutePath)
                putExtra("EXTRA_PRESET_NAME", presetName)
                if (activePresetId != null) {
                    putExtra("EXTRA_APPEND_TO_PRESET_ID", activePresetId)
                }
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
        latestBitmap?.recycle()
        latestBitmap = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stoppedByUser = true
        if (overlayView != null) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            overlayView = null
        }
        mediaProjection?.stop()
        cleanupProjection()
    }
}
