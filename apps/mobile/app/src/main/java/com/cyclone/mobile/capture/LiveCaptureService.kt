package com.cyclone.mobile.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import com.cyclone.mobile.ai.vision.live.FrameSourceType
import com.cyclone.mobile.ai.vision.live.LiveVisionRuntime
import com.cyclone.mobile.runtime.session.ExecutionSession

/** Physical-display capture only. This service never claims to capture a hidden workspace. */
class LiveCaptureService : Service() {
    private val thread = HandlerThread("cyclone-live-capture")
    private lateinit var handler: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var revision = 0L
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width > 0 && height > 0 && display != null) {
                attachReader(width, height)
                display?.resize(width, height, resources.configuration.densityDpi)
                display?.surface = reader?.surface
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread.start()
        handler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        val consent = intent?.getParcelableExtra("consent", Intent::class.java)
        if (consent == null || intent.getIntExtra("resultCode", 0) != android.app.Activity.RESULT_OK) {
            stopSelf(); return START_NOT_STICKY
        }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Live phone view", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, LiveCaptureService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(901, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Cyclone live view is on")
            .setContentText("Your screen is available to your active Cyclone task.")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        try {
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(android.app.Activity.RESULT_OK, consent)
            projection!!.registerCallback(callback, handler)
            val size = getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
            revision = LiveVisionRuntime.startSource(SESSION, 0, FrameSourceType.MEDIA_PROJECTION)
            attachReader(size.width(), size.height())
            display = projection!!.createVirtualDisplay("Cyclone physical live view", size.width(), size.height(),
                resources.configuration.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
        } catch (_: RuntimeException) { stopSelf() }
        return START_NOT_STICKY
    }

    private fun attachReader(width: Int, height: Int) {
        reader?.close()
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { images ->
            images.setOnImageAvailableListener({ source ->
                runCatching {
                    source.acquireLatestImage()?.use { image ->
                        val plane = image.planes.first()
                        val paddedWidth = plane.rowStride / plane.pixelStride
                        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                        val cropped = try {
                            padded.copyPixelsFromBuffer(plane.buffer)
                            Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                        } catch (error: Throwable) { padded.recycle(); throw error }
                        if (cropped !== padded) padded.recycle()
                        LiveVisionRuntime.publish(SESSION, 0, FrameSourceType.MEDIA_PROJECTION, revision,
                            image.timestamp / 1_000_000, cropped)
                    }
                }
            }, handler)
        }
    }

    override fun onDestroy() {
        LiveVisionRuntime.stopSource(SESSION)
        projection?.unregisterCallback(callback)
        display?.release()
        reader?.close()
        projection?.stop()
        thread.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "cyclone-live-capture"
        private const val STOP = "com.cyclone.mobile.capture.STOP"
        private const val SESSION = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID
    }
}
