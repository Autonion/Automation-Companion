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
                startForegroundServiceNotification()
                showOverlay()
            }
            "ACTION_SHOW_OVERLAY" -> {
                // Called after editor finishes — re-show the overlay
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
            .setSmallIcon(R.mipmap.ic_launcher)
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
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) isDragging = true
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
        windowManager?.addView(overlayView, lp)
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
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

            // DON'T stopSelf() — keep service alive, overlay will re-show on ACTION_SHOW_OVERLAY
        } catch (e: Exception) {
            Log.e(TAG, "Error saving capture", e)
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
