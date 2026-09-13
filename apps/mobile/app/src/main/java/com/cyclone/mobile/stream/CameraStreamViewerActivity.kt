package com.cyclone.mobile.stream

import android.app.Activity
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen Cyclone camera viewer launched by Cyclone One over the existing USB/ADB fleet.
 *
 * The source dimensions are authoritative. [NativeAspectSurfaceView] uses contain semantics, so
 * 1920x1440 stays 4:3 and 1440x1920 stays 3:4. There is deliberately no 16:9 fallback, crop or
 * stretch path in this activity.
 */
class CameraStreamViewerActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: NativeAspectSurfaceView
    private lateinit var statusView: TextView
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null
    private var decoder: H264ViewerDecoder? = null
    private var streamUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        if (!isSafeLoopbackStream(streamUrl)) {
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = NativeAspectSurfaceView(this).also { view ->
            view.holder.addCallback(this)
            view.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
        }
        statusView = TextView(this).apply {
            text = "Connecting camera…"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            textSize = 13f
            setPadding(dp(12), dp(7), dp(12), dp(7))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(18) }
        }
        root.addView(surfaceView)
        root.addView(statusView)
        setContentView(root)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (socket != null) return
        decoder = H264ViewerDecoder(holder.surface) { width, height ->
            runOnUiThread {
                surfaceView.setVideoSize(width, height)
                statusView.text = "LIVE · ${width}×${height} · native frame"
                statusView.postDelayed({ statusView.animate().alpha(0f).setDuration(220).start() }, 1800)
            }
        }
        socket = client.newWebSocket(
            Request.Builder().url(streamUrl).build(),
            StreamListener(),
        )
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        socket?.close(1000, "surface destroyed")
        socket = null
        decoder?.close()
        decoder = null
    }

    override fun onDestroy() {
        socket?.cancel()
        socket = null
        decoder?.close()
        decoder = null
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    private inner class StreamListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            runOnUiThread { statusView.text = "Camera connected · waiting for first frame…" }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (json.optString("type")) {
                "hello" -> {
                    if (!json.optBoolean("preserveSourceAspect", false)) {
                        webSocket.close(1003, "native aspect required")
                    }
                }
                "session" -> {
                    val width = json.optInt("width")
                    val height = json.optInt("height")
                    if (width > 0 && height > 0) decoder?.setFormat(width, height)
                }
                "state" -> {
                    val state = json.optString("state")
                    if (state == "UNAVAILABLE" || state == "STOPPED") {
                        runOnUiThread { finish() }
                    }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (bytes.size < PACKET_HEADER_BYTES) return
            val data = bytes.toByteArray()
            val flags = data[0].toInt() and 0xff
            val ptsUs = ByteBuffer.wrap(data, 1, 8).long
            decoder?.offer(
                EncodedPacket(
                    payload = data.copyOfRange(PACKET_HEADER_BYTES, data.size),
                    ptsUs = ptsUs,
                    codecConfig = flags and 0x01 != 0,
                    keyFrame = flags and 0x02 != 0,
                ),
            )
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            runOnUiThread {
                statusView.alpha = 1f
                statusView.text = "Stream disconnected"
                statusView.postDelayed({ if (!isFinishing) finish() }, 900)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            runOnUiThread { if (!isFinishing) finish() }
        }
    }

    private fun isSafeLoopbackStream(value: String): Boolean =
        value.startsWith("ws://127.0.0.1:") && value.contains("/v1/camera-stream/ws/")

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_STREAM_URL = "cyclone_stream_url"
        private const val PACKET_HEADER_BYTES = 9
    }
}

private data class EncodedPacket(
    val payload: ByteArray,
    val ptsUs: Long,
    val codecConfig: Boolean,
    val keyFrame: Boolean,
)

/**
 * Small MediaCodec worker. Packets are bounded so a slow viewer drops old video instead of adding
 * seconds of latency. The encoded source is never resized here; Surface layout is presentation-only.
 */
private class H264ViewerDecoder(
    private val surface: Surface,
    private val onVideoSize: (Int, Int) -> Unit,
) : AutoCloseable {
    private val packets = LinkedBlockingQueue<EncodedPacket>(8)
    private val running = AtomicBoolean(true)
    @Volatile private var width = 0
    @Volatile private var height = 0
    private var codec: MediaCodec? = null
    private val worker = Thread(::runDecoder, "cyclone-camera-viewer").apply {
        isDaemon = true
        start()
    }

    fun setFormat(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        this.width = width
        this.height = height
        onVideoSize(width, height)
    }

    fun offer(packet: EncodedPacket) {
        if (!running.get()) return
        if (!packets.offer(packet)) {
            packets.poll()
            packets.offer(packet)
        }
    }

    private fun runDecoder() {
        try {
            while (running.get()) {
                if (codec == null) {
                    if (width <= 0 || height <= 0) {
                        Thread.sleep(8)
                        continue
                    }
                    codec = MediaCodec.createDecoderByType(MIME).also { decoder ->
                        val format = MediaFormat.createVideoFormat(MIME, width, height)
                        decoder.configure(format, surface, null, 0)
                        decoder.start()
                    }
                }

                val packet = packets.poll(200, TimeUnit.MILLISECONDS)
                if (packet != null) queuePacket(codec ?: continue, packet)
                drain(codec ?: continue)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Throwable) {
            // WebSocket lifecycle owns user-visible recovery. Decoder failure simply ends this viewer.
        } finally {
            releaseCodec()
        }
    }

    private fun queuePacket(decoder: MediaCodec, packet: EncodedPacket) {
        val inputIndex = decoder.dequeueInputBuffer(4_000)
        if (inputIndex < 0) return
        val input = decoder.getInputBuffer(inputIndex) ?: return
        input.clear()
        if (packet.payload.size > input.remaining()) {
            decoder.queueInputBuffer(inputIndex, 0, 0, packet.ptsUs, 0)
            return
        }
        input.put(packet.payload)
        val flags = when {
            packet.codecConfig -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            packet.keyFrame -> MediaCodec.BUFFER_FLAG_KEY_FRAME
            else -> 0
        }
        decoder.queueInputBuffer(inputIndex, 0, packet.payload.size, packet.ptsUs, flags)
    }

    private fun drain(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = decoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val output = decoder.outputFormat
                    val nextWidth = output.getInteger(MediaFormat.KEY_WIDTH)
                    val nextHeight = output.getInteger(MediaFormat.KEY_HEIGHT)
                    if (nextWidth > 0 && nextHeight > 0) onVideoSize(nextWidth, nextHeight)
                }
                else -> if (outputIndex >= 0) decoder.releaseOutputBuffer(outputIndex, true)
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        packets.clear()
        worker.interrupt()
        runCatching { worker.join(700) }
        releaseCodec()
    }

    @Synchronized
    private fun releaseCodec() {
        val decoder = codec ?: return
        codec = null
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
    }

    companion object {
        private const val MIME = "video/avc"
    }
}

/** SurfaceView whose measured bounds always contain the source frame without changing its ratio. */
private class NativeAspectSurfaceView(activity: Activity) : SurfaceView(activity) {
    private var videoWidth = 0
    private var videoHeight = 0

    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        videoWidth = width
        videoHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = View.MeasureSpec.getSize(heightMeasureSpec)
        if (videoWidth <= 0 || videoHeight <= 0 || availableWidth <= 0 || availableHeight <= 0) {
            setMeasuredDimension(availableWidth, availableHeight)
            return
        }

        val sourceRatio = videoWidth.toDouble() / videoHeight.toDouble()
        val boxRatio = availableWidth.toDouble() / availableHeight.toDouble()
        val measuredWidth: Int
        val measuredHeight: Int
        if (boxRatio > sourceRatio) {
            measuredHeight = availableHeight
            measuredWidth = (measuredHeight * sourceRatio).toInt()
        } else {
            measuredWidth = availableWidth
            measuredHeight = (measuredWidth / sourceRatio).toInt()
        }
        setMeasuredDimension(measuredWidth.coerceAtLeast(1), measuredHeight.coerceAtLeast(1))
    }
}
