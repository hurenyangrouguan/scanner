package com.example.docscanner.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.docscanner.edit.EditActivity
import com.example.docscanner.model.ScannedPage
import com.example.docscanner.model.ScanSession
import io.github.itshedimisawi.vitro.Glass
import io.github.itshedimisawi.vitro.glassBackdrop
import io.github.itshedimisawi.vitro.glassEffect
import io.github.itshedimisawi.vitro.rememberGlassBackdrop
import java.io.File

class CameraScanActivity : ComponentActivity() {

    private var imageCapture: ImageCapture? = null

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) setContent { ScannerScreen() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            setContent { ScannerScreen() }
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    @Composable
    private fun ScannerScreen() {
        val lifecycleOwner = LocalLifecycleOwner.current
        val backdrop = rememberGlassBackdrop()

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        val future = ProcessCameraProvider.getInstance(ctx)
                        future.addListener({
                            val provider = future.get()

                            val resolutionSelector = ResolutionSelector.Builder()
                                .setAllowedResolutionMode(
                                    ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
                                )
                                .setResolutionStrategy(
                                    ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
                                )
                                .build()

                            val preview = Preview.Builder()
                                .setResolutionSelector(resolutionSelector)
                                .build()
                                .also { it.setSurfaceProvider(surfaceProvider) }

                            imageCapture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .setResolutionSelector(resolutionSelector)
                                .build()

                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture
                            )
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
                modifier = Modifier.fillMaxSize().glassBackdrop(backdrop)
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 64.dp)
                    .size(80.dp)
                    .glassEffect(backdrop, Glass.Regular.interactive(), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Button(
                    onClick = { takePhoto() },
                    shape = CircleShape,
                    modifier = Modifier.fillMaxSize(0.78f)
                ) {}
            }
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val file = File(externalCacheDir, "scan/raw_${System.currentTimeMillis()}.jpg")
            .apply { parentFile?.mkdirs() }

        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    val page = ScannedPage(rawPath = file.absolutePath)
                    ScanSession.add(page)
                    startActivity(
                        Intent(this@CameraScanActivity, EditActivity::class.java)
                            .putExtra(EditActivity.EXTRA_PAGE_ID, page.id)
                    )
                    finish()
                }

                override fun onError(exc: ImageCaptureException) {
                    android.util.Log.e("CameraScan", "Capture failed", exc)
                }
            }
        )
    }
}
