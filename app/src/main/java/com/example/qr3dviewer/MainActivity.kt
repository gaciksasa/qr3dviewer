package com.example.qr3dviewer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var sceneView: SceneView
    private lateinit var backButton: Button
    private lateinit var scanningOverlay: FrameLayout
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var isScanning = true
    private var modelNode: ModelNode? = null
    private var cameraProvider: ProcessCameraProvider? = null

    companion object {
        private const val TAG = "QR3DViewer"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        viewFinder = findViewById(R.id.viewFinder)
        sceneView = findViewById(R.id.sceneView)
        backButton = findViewById(R.id.backButton)
        scanningOverlay = findViewById(R.id.scanningOverlay)

        // Set up back button
        backButton.setOnClickListener {
            returnToScanner()
        }

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, QRCodeAnalyzer { qrCode ->
                        if (isScanning) {
                            isScanning = false
                            runOnUiThread {
                                processQRCode(qrCode)
                            }
                        }
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(this, getString(R.string.camera_init_failed), Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processQRCode(qrCode: String) {
        Toast.makeText(this, getString(R.string.qr_code_detected, qrCode), Toast.LENGTH_SHORT).show()

        // Stop camera to save resources
        cameraProvider?.unbindAll()

        // Hide camera preview and show 3D scene
        viewFinder.visibility = View.GONE
        scanningOverlay.visibility = View.GONE
        sceneView.visibility = View.VISIBLE
        backButton.visibility = View.VISIBLE

        // Load 3D model based on QR code content
        load3DModel(qrCode)
    }

    private fun load3DModel(qrContent: String) {
        // Parse QR content - expecting format like "model:cube" or URL
        val modelInfo = parseQRContent(qrContent)

        try {
            // Remove any existing model
            modelNode?.let {
                sceneView.removeChild(it)
                it.destroy()
            }

            modelNode = ModelNode().apply {
                // Load model based on QR content
                when (modelInfo.type) {
                    ModelType.PRIMITIVE -> {
                        // For demo: create primitive shapes
                        loadModelGlbAsync(
                            glbFileLocation = getModelPath(modelInfo.modelId),
                            autoAnimate = true,
                            scaleToUnits = 1f,
                            centerOrigin = Position(0f, 0f, -2f)
                        ) {
                            sceneView.addChild(this)
                        }
                    }
                    ModelType.URL -> {
                        // Load from URL (implementation depends on your server setup)
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.loading_from_url, modelInfo.modelId),
                            Toast.LENGTH_SHORT
                        ).show()
                        // For now, fallback to default model
                        loadDefaultModel()
                    }
                    ModelType.ASSET -> {
                        // Load from assets
                        loadModelGlbAsync(
                            glbFileLocation = "models/${modelInfo.modelId}.glb",
                            autoAnimate = true,
                            scaleToUnits = 1f,
                            centerOrigin = Position(0f, 0f, -2f)
                        ) {
                            sceneView.addChild(this)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading 3D model", e)
            Toast.makeText(this, getString(R.string.error_loading_model, e.message), Toast.LENGTH_LONG).show()
            returnToScanner()
        }
    }

    private fun loadDefaultModel() {
        // Create a simple colored cube as default when no model file is available
        modelNode?.let {
            sceneView.addChild(it)
        }
    }

    private fun parseQRContent(qrContent: String): ModelInfo {
        return when {
            qrContent.startsWith("http://") || qrContent.startsWith("https://") -> {
                ModelInfo(ModelType.URL, qrContent)
            }
            qrContent.startsWith("model:") -> {
                val modelId = qrContent.substring(6)
                ModelInfo(ModelType.ASSET, modelId)
            }
            else -> {
                // Default: treat as primitive shape name
                ModelInfo(ModelType.PRIMITIVE, qrContent.lowercase())
            }
        }
    }

    private fun getModelPath(modelName: String): String {
        return when (modelName) {
            "cube" -> "models/cube.glb"
            "sphere" -> "models/sphere.glb"
            "cylinder" -> "models/cylinder.glb"
            "torus" -> "models/torus.glb"
            else -> "models/default.glb"
        }
    }

    private fun returnToScanner() {
        // Clean up 3D scene
        modelNode?.let {
            sceneView.removeChild(it)
            it.destroy()
            modelNode = null
        }

        // Hide 3D view and show camera
        sceneView.visibility = View.GONE
        backButton.visibility = View.GONE
        viewFinder.visibility = View.VISIBLE
        scanningOverlay.visibility = View.VISIBLE

        // Reset scanning flag
        isScanning = true

        // Restart camera
        startCamera()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        modelNode?.destroy()
    }

    override fun onResume() {
        super.onResume()
        sceneView.resume()
    }

    override fun onPause() {
        super.onPause()
        sceneView.pause()
    }

    // Data classes
    data class ModelInfo(val type: ModelType, val modelId: String)

    enum class ModelType {
        PRIMITIVE,  // Basic shapes
        ASSET,      // Local asset file
        URL         // Remote URL
    }

    // QR Code Analyzer
    private class QRCodeAnalyzer(private val onQRCodeDetected: (String) -> Unit) : ImageAnalysis.Analyzer {

        private val scanner = BarcodeScanning.getClient()

        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            barcode.rawValue?.let { value ->
                                if (barcode.format == Barcode.FORMAT_QR_CODE) {
                                    onQRCodeDetected(value)
                                }
                            }
                        }
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            }
        }
    }
}