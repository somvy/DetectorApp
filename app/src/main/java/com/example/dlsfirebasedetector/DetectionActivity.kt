package com.example.dlsfirebasedetector

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.TextPaint
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toRectF
import androidx.lifecycle.LifecycleOwner
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetectorOptions
import com.google.firebase.ml.vision.objects.FirebaseVisionObject
import com.google.firebase.ml.vision.objects.FirebaseVisionObjectDetector
import com.google.firebase.ml.vision.objects.FirebaseVisionObjectDetectorOptions
import kotlinx.android.synthetic.main.activity_detection.*
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias DetectionListener = (detectedObjects: MutableList<FirebaseVisionObject>) -> Unit


class DetectionActivity : AppCompatActivity() {
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detection)

        if (allPermissionGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                DetectionActivity.REQUIRED_PERMISSIONS,
                DetectionActivity.REQUEST_CODE_PERMISSIONS
            )
        }
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        //super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == DetectionActivity.REQUEST_CODE_PERMISSIONS) {
            if (allPermissionGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by user", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun allPermissionGranted() = DetectionActivity.REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists()) mediaDir else filesDir
    }

    lateinit var overlay: Bitmap

    @SuppressLint("RestrictedApi")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(Runnable {

            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            preview = Preview.Builder().build()

            val analyzer = DetectionAnalyzer { detectedObjects ->
                run {
                    val bitmap = viewFinder.bitmap ?: return@DetectionAnalyzer
                    val colorMain = Color.argb(150, 0, 150, 252)
                    overlay = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
                    val paint = Paint().apply {
                        isAntiAlias = true
                        style = Paint.Style.STROKE
                        color = colorMain
                        strokeWidth = 10f
                    }
                    val textPaint = TextPaint().apply {
                        isAntiAlias = true
                        style = Paint.Style.FILL_AND_STROKE
                        color = Color.argb(255, 0, 80, 115)
                        textSize = 30f
                        density = 1.2f
                        textAlign = Paint.Align.CENTER
                    }

                    if (detectedObjects.toTypedArray().isNotEmpty()) {

                        for (obj in detectedObjects) {
                            val textCategory: String = when (obj.classificationCategory) {
                                FirebaseVisionObject.CATEGORY_FASHION_GOOD -> "Fashion Good"
                                FirebaseVisionObject.CATEGORY_FOOD -> "Food"
                                FirebaseVisionObject.CATEGORY_HOME_GOOD -> "Home Good"
                                FirebaseVisionObject.CATEGORY_PLACE -> "Place"
                                FirebaseVisionObject.CATEGORY_PLANT -> "Plant"
                                FirebaseVisionObject.CATEGORY_UNKNOWN -> "Unknown"
                                else -> "Unknown"
                            }
                            val canvas = Canvas(overlay)
                            canvas.drawRect(obj.boundingBox.toRectF(), paint)
                            canvas.drawText(
                                textCategory,
                                obj.boundingBox.centerX().toFloat(),
                                obj.boundingBox.centerY().toFloat(),
                                textPaint
                            )
                            Canvas(overlay).apply {
                                canvas
                            }
                        }
                    }
                }
                runOnUiThread {
                    imageView.x = viewFinder.x
                    imageView.y = viewFinder.y
                    imageView.setImageBitmap(overlay)

                }
            }

            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(1080, 1920))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(cameraExecutor, analyzer)
                }
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this as LifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
                preview?.setSurfaceProvider(viewFinder.createSurfaceProvider())
            } catch (e: Exception) {
                Log.e(DetectionActivity.TAG, "Use case binding failed: $e")
            }

        }, ContextCompat.getMainExecutor(this))
    }

    companion object {
        private const val TAG = "DetectionModule"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}

private class DetectionAnalyzer(private val listener: DetectionListener) : ImageAnalysis.Analyzer {

    private fun degreesToFirebaseRotation(degrees: Int): Int = when (degrees) {
        0 -> FirebaseVisionImageMetadata.ROTATION_0
        90 -> FirebaseVisionImageMetadata.ROTATION_90
        180 -> FirebaseVisionImageMetadata.ROTATION_180
        270 -> FirebaseVisionImageMetadata.ROTATION_270
        else -> throw Exception("Rotation must be 0, 90, 180 or 270.")
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    override fun analyze(image: ImageProxy) {
        val mediaImage = image.image
        val imageRotation = degreesToFirebaseRotation(image.imageInfo.rotationDegrees)
        if (mediaImage != null) {
            val image2analyze = FirebaseVisionImage.fromMediaImage(mediaImage, imageRotation)
            //cameraX format YUV_420_888

            val options = FirebaseVisionObjectDetectorOptions.Builder()
                .setDetectorMode(FirebaseVisionFaceDetectorOptions.FAST)
                .enableClassification()
                .enableMultipleObjects()
                .build()
            val detector: FirebaseVisionObjectDetector =
                FirebaseVision.getInstance().getOnDeviceObjectDetector(options)

            detector.processImage(image2analyze)
                .addOnSuccessListener { detectedObjectsRaw ->

                    val detectedObjects =
                        MutableList<FirebaseVisionObject>(detectedObjectsRaw.size) { x -> detectedObjectsRaw[x] }
                    listener(detectedObjects)

                }
                .addOnFailureListener { e ->
                    Log.e("DetectionModule", "Error occured $e")
                }
            image.close()
        }
    }
}