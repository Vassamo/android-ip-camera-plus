package com.github.digitallyrefined.androidipcamera

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.NetworkInterface
import java.util.concurrent.Executors

class MjpegStreamService : Service() {

    private var cameraProvider: ProcessCameraProvider? = null
    private var streamingServer: StreamingServer? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var fps: Int = 5
    private var lastFrameTime = 0L

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
        initStreaming()
    }

    override fun onDestroy() {
        serviceScope.cancel()

        streamingServer?.stop()
        cameraProvider?.unbindAll()

        cameraExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "mjpeg_channel"
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("MJPEG Stream")
            .setContentText("Streaming MJPEG w tle")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "mjpeg_channel",
                "MJPEG Stream Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun initStreaming() {
        serviceScope.launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@MjpegStreamService)
            fps = prefs.getString("stream_fps", "5")?.toIntOrNull() ?: 5

            streamingServer = StreamingServer(this@MjpegStreamService, fps)
            streamingServer?.start()

            startCameraStream()
        }
    }

    private fun startCameraStream() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            analysis.setAnalyzer(cameraExecutor) { image ->
                Log.d(TAG, "Camera frame received")

                try {
                    val delay = if (fps <= 0) 0L else 1000L / fps
                    val currentTime = System.currentTimeMillis()

                    if (currentTime - lastFrameTime >= delay) {
                        lastFrameTime = currentTime
                        val jpegBytes = imageProxyToJpeg(image)
                        streamingServer?.sendFrame(jpegBytes)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Analyzer error: ${e.message}")
                } finally {
                    image.close()
                }
            }


            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    DummyLifecycleOwner(),
                    cameraSelector,
                    analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageProxyToJpeg(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, outputStream)
        return outputStream.toByteArray()
    }


    companion object {
        private const val TAG = "MjpegStreamService"
    }
}

// DummyLifecycleOwner: używany ponieważ Service nie implementuje LifecycleOwner
class DummyLifecycleOwner : LifecycleOwner {
    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this).apply {
        currentState = Lifecycle.State.STARTED
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
}
