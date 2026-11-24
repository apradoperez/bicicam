package es.bicilab.bicicam

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import es.bicilab.bicicam.camera.CameraController
import es.bicilab.bicicam.extension.RecordingFrame
import es.bicilab.bicicam.screens.KarooBackButton
import es.bicilab.bicicam.screens.MainScreen
import es.bicilab.bicicam.theme.AppTheme

class MainActivity : ComponentActivity() {
    private var hasPermission by mutableStateOf(false)
    private var canDrawFrame by mutableStateOf(false)

    private val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = CameraController.hasBluetoothPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasPermission = CameraController.hasBluetoothPermission()
        if (!hasPermission) permissionRequest.launch(permissions)

        val content = ComposeView(this).apply {
            setContent {
                AppTheme {
                    // El botón va encima de la pantalla, como el de volver del propio Karoo
                    Box(modifier = Modifier.fillMaxSize()) {
                        MainScreen(
                            hasPermission = hasPermission,
                            onRequestPermission = { permissionRequest.launch(permissions) },
                            canDrawFrame = canDrawFrame,
                        )
                        KarooBackButton(
                            onClick = { onBackPressedDispatcher.onBackPressed() },
                            modifier = Modifier.align(Alignment.BottomStart),
                        )
                    }
                }
            }
        }
        setContentView(KarooBackButtonLayout(this) { onBackPressedDispatcher.onBackPressed() }.apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        // Al volver de los ajustes del Karoo puede haberse dado el permiso del marco
        canDrawFrame = RecordingFrame.hasPermission(this)
        RecordingFrame.recheck()
    }

    override fun onStart() {
        super.onStart()
        // Mantiene la cámara conectada mientras la app está abierta
        CameraController.acquire()
    }

    override fun onStop() {
        CameraController.release()
        super.onStop()
    }
}
