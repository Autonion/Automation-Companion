package com.autonion.automationcompanion.features.screen_understanding_ml.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.autonion.automationcompanion.features.screen_understanding_ml.core.ScreenUnderstandingService
import com.autonion.automationcompanion.ui.theme.AppTheme

class CaptureEditorActivity : ComponentActivity() {
    private lateinit var model: CaptureEditorViewModel
    private val ownsCaptureSession get() = !intent.hasExtra("PRESET_ID")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[CaptureEditorViewModel::class.java]
        model.load(intent)
        setContent {
            AppTheme(darkTheme = true) {
                CaptureEditorScreen(model,
                    onCancel = { finish() },
                    onSaved = {
                        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                        if (ownsCaptureSession) moveTaskToBack(true)
                        finish()
                    },
                    onRecapture = { draftPath ->
                        if (draftPath != null) {
                            startActivity(Intent(this, SetupFlowActivity::class.java).apply {
                                putExtra("presetName", model.name)
                                putExtra("EXTRA_EDITOR_DRAFT_PATH", draftPath)
                            })
                        } else {
                            Toast.makeText(this, "Navigate to the screen, then tap Snap", Toast.LENGTH_SHORT).show()
                            moveTaskToBack(true)
                        }
                        finish()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (ownsCaptureSession) ScreenUnderstandingService.instance?.setOverlayVisibility(false)
    }

    override fun onPause() {
        if (ownsCaptureSession) ScreenUnderstandingService.instance?.setOverlayVisibility(true)
        super.onPause()
    }
}
