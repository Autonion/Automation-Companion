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

    private var isCapturing = false

    private fun captureScreen() {
        if (resultData == null) {
            android.widget.Toast.makeText(this, "Error: Missing screen capture permission data", android.widget.Toast.LENGTH_SHORT).show()
            Log.e(TAG, "resultData is null in captureScreen()")
            return
        }

        // Hide overlay before capture
        overlayView?.visibility = View.GONE

        // Wait for UI to settle (500ms)
        Handler(Looper.getMainLooper()).postDelayed({
            initProjectionAndCapture()
        }, 500)
    }

    private fun initProjectionAndCapture() {
        try {
            if (mediaProjection == null) {
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

                // Create ImageReader
                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                
                // Set listener to process frames or discard them
                imageReader?.setOnImageAvailableListener({ reader ->
                    val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
                    if (image != null) {
                        if (isCapturing) {
                            isCapturing = false
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

                                // Stop rendering to save battery until next capture
                                virtualDisplay?.surface = null

                                saveAndOpenEditor(finalBitmap)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing frame", e)
                                overlayView?.post { overlayView?.visibility = View.VISIBLE }
                            }
                        }
                        image.close()
                    }
                }, Handler(Looper.getMainLooper()))

                // Create VirtualDisplay with NULL surface initially to prevent unnecessary rendering
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCapture",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    null,
                    null,
                    null
                )
            }

            if (virtualDisplay == null) {
                Log.e(TAG, "Failed to create virtual display")
                overlayView?.visibility = View.VISIBLE
                return
            }

            // Drain any stale buffered frames from previous captures
            try {
                var staleCount = 0
                while (true) {
                    val stale = imageReader?.acquireLatestImage()
                    if (stale != null) { stale.close(); staleCount++ } else break
                }
                if (staleCount > 0) Log.d(TAG, "Drained $staleCount stale frame(s)")
            } catch (_: Exception) {}

            // Connect surface first, then wait a beat for a fresh frame to render
            virtualDisplay?.surface = imageReader?.surface
            Log.d(TAG, "Virtual display connected, waiting for fresh frame...")

            // Delay slightly so the virtual display renders a FRESH frame (not stale buffer)
            Handler(Looper.getMainLooper()).postDelayed({
                isCapturing = true
            }, 350)

        } catch (e: Exception) {
            Log.e(TAG, "Error in initProjectionAndCapture", e)
            DebugLogger.error(applicationContext, LogCategory.VISUAL_TRIGGER, "Projection Error", "Error starting projection: ${e.message}", TAG)
            overlayView?.visibility = View.VISIBLE
        }
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
