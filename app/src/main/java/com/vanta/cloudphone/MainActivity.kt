package com.vanta.cloudphone

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.widget.FrameLayout
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var surfaceViewRenderer: SurfaceViewRenderer
    private lateinit var eglBase: EglBase
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var peerConnection: PeerConnection
    private lateinit var videoTrack: VideoTrack
    private lateinit var dataChannel: DataChannel
    private var webSocket: WebSocket? = null

    private var remoteWidth = 720
    private var remoteHeight = 1280

    private val signalUrl = "wss://your-host.example.com/signal"

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        surfaceViewRenderer = SurfaceViewRenderer(this)
        root.addView(surfaceViewRenderer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)

        initWebRtc()
        connectSignaling()

        surfaceViewRenderer.setOnTouchListener { _, event -> handleTouch(event); true }
    }

    private fun initWebRtc() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .createInitializationOptions()
        )
        eglBase = EglBase.create()
        surfaceViewRenderer.init(eglBase.eglBaseContext, null)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        videoTrack = peerConnectionFactory.createVideoTrack("remote_video", null)
        videoTrack.addSink(surfaceViewRenderer)
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    sendSignal(JSONObject().apply {
                        put("type", "ice")
                        put("candidate", candidate.sdp)
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                    })
                }

                override fun onTrack(transceiver: RtpTransceiver) {
                    val track = transceiver.receiver.track()
                    if (track is VideoTrack) {
                        runOnUiThread { track.addSink(surfaceViewRenderer) }
                    }
                }

                override fun onDataChannel(channel: DataChannel) {
                    dataChannel = channel
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    if (state == PeerConnection.IceConnectionState.FAILED) {
                        runOnUiThread { recreateConnection() }
                    }
                }

                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            }
        )

        peerConnection.addTrack(videoTrack, listOf("stream0"))
        dataChannel = peerConnection.createDataChannel("control", DataChannel.Init())
    }

    private fun connectSignaling() {
        val client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(signalUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                createPeerConnection()
                sendSignal(JSONObject().put("type", "join"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = JSONObject(text)
                when (msg.optString("type")) {
                    "offer" -> {
                        val sdp = msg.getString("sdp")
                        peerConnection.setRemoteDescription(
                            SessionDescription(SessionDescription.Type.OFFER, sdp)
                        )
                        peerConnection.createAnswer({ answer ->
                            peerConnection.setLocalDescription(answer)
                            sendSignal(JSONObject().apply {
                                put("type", "answer")
                                put("sdp", answer.description)
                            })
                        }, { error -> error.printStackTrace() })
                    }
                    "ice" -> {
                        peerConnection.addIceCandidate(IceCandidate(
                            msg.getString("sdpMid"),
                            msg.getInt("sdpMLineIndex"),
                            msg.getString("candidate")
                        ))
                    }
                }
            }
        })
    }

    private fun sendSignal(json: JSONObject) {
        webSocket?.send(json.toString())
    }

    private fun handleTouch(event: MotionEvent) {
        if (!::dataChannel.isInitialized || dataChannel.state() != DataChannel.State.OPEN) return

        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> 0
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> 1
            MotionEvent.ACTION_MOVE -> 2
            else -> return
        }

        for (i in 0 until event.pointerCount) {
            val x = (event.getX(i) / surfaceViewRenderer.width * remoteWidth).toInt()
            val y = (event.getY(i) / surfaceViewRenderer.height * remoteHeight).toInt()

            val payload = JSONObject().apply {
                put("type", "touch")
                put("action", action)
                put("pointerId", event.getPointerId(i))
                put("x", x)
                put("y", y)
                put("screenWidth", remoteWidth)
                put("screenHeight", remoteHeight)
            }
            dataChannel.send(DataChannel.Buffer(
                java.nio.ByteBuffer.wrap(payload.toString().toByteArray()),
                false
            ))
        }
    }

    private fun recreateConnection() {
        peerConnection.close()
        createPeerConnection()
    }

    override fun onDestroy() {
        webSocket?.close(1000, "bye")
        if (::peerConnection.isInitialized) peerConnection.close()
        if (::peerConnectionFactory.isInitialized) peerConnectionFactory.dispose()
        if (::eglBase.isInitialized) eglBase.release()
        super.onDestroy()
    }
}
