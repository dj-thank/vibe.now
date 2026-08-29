package app.setlog.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import app.setlog.capture.input.VolumeKeyController
import app.setlog.capture.model.ShortcutAction
import app.setlog.capture.ui.SetLogApp
import app.setlog.capture.ui.theme.SetLogTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    private val viewModel: SetLogViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        publishPermissionState()
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::importVideo)
    }

    private val volumeController by lazy {
        VolumeKeyController(
            settingsProvider = viewModel::inputSettingsSnapshot,
            callback = object : VolumeKeyController.Callback {
                override fun onRecordHoldStarted(pressedAtEpochMs: Long) {
                    viewModel.onRecordHoldStarted(pressedAtEpochMs)
                }

                override fun onRecordHoldEnded() {
                    viewModel.onRecordHoldEnded()
                }

                override fun onShortcutAction(action: ShortcutAction) {
                    when (action) {
                        ShortcutAction.FINISH -> {
                            performFinishHaptic()
                            viewModel.finishCurrentSession()
                        }
                        ShortcutAction.OPEN_GALLERY -> viewModel.openGalleryWithoutFinishing()
                        ShortcutAction.NONE -> Unit
                    }
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        publishPermissionState()

        setContent {
            SetLogTheme {
                SetLogApp(
                    viewModel = viewModel,
                    onRequestPermissions = ::requestPermissions,
                    onOpenSettings = ::openAppSettings,
                    onImportVideo = { videoPickerLauncher.launch(arrayOf("video/*")) },
                )
            }
        }

        if (!hasPermission(Manifest.permission.CAMERA)) {
            requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        publishPermissionState()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return if (volumeController.dispatch(event)) {
            true
        } else {
            super.dispatchKeyEvent(event)
        }
    }

    override fun onStop() {
        volumeController.reset()
        viewModel.onAppBackgrounded()
        super.onStop()
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
            ),
        )
    }

    private fun publishPermissionState() {
        viewModel.setPermissions(
            cameraGranted = hasPermission(Manifest.permission.CAMERA),
            microphoneGranted = hasPermission(Manifest.permission.RECORD_AUDIO),
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun performFinishHaptic() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        val pattern = longArrayOf(0L, 70L, 45L, 140L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            vibrator.vibrate(pattern, -1)
        }
    }
}
