package Com.hau.name.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

private const val TAG = "PeerConnectionManager"

// Bitrate cho track video màn hình (Máy B -> Máy A). 1280px cạnh dài @20fps không cần quá cao;
// đặt trần vừa phải để tránh nghẽn khi đi qua TURN relay trên mạng di động, đồng thời đặt sàn
// đủ để chữ trên màn hình còn đọc được khi mạng tốt.
private const val MAX_VIDEO_BITRATE_BPS = 2_000_000
private const val MIN_VIDEO_BITRATE_BPS = 300_000

// Gui tep/anh qua DataChannel: kich thuoc 1 doan - nang tu 16KB len 64KB. Ca 2 dau (dien
// thoai va app Qrtuxa may tinh) deu la native WebRTC (khong phai trinh duyet), nen khong bi
// gioi han 16KB cua mot so trinh duyet cu - 64KB van nam rat sau duoi gioi han message SCTP
// thong thuong (256KB+) nhung giam dang ke so lan goi send()/JNI overhead so voi 16KB, giup
// truyen anh/tep NHANH HON RO RET voi CUNG mot noi dung goc (KHONG nen/giam do phan giai anh -
// van gui du 100% byte goc cua anh, chi thay doi cach CHIA NHO de truyen di).
private const val FILE_CHUNK_SIZE = 64 * 1024

// Kiem soat luong (flow control) khi gui: KHONG bom tat ca cac doan vao kenh lien tuc khong
// ngung nghi - lam vay se lam hang doi gui noi bo cua SCTP phinh to khong kiem soat (buffered
// amount tang vot), gay ra dung/giat cuc bo va trong truong hop xau co the vuot gioi han bo dem
// cua thu vien WebRTC khien kenh bi dong giua chung (gui that bai). Thay vao do: cho gui doan
// tiep theo khi luong dang cho gui (bufferedAmount) giam xuong duoi muc thap - dung ki thuat
// nay giup toc do gui bam sat toc do THUC TE cua duong truyen (nhanh nhat co the ma van an
// toan), giong y het cach video stream tu dieu chinh theo bang thong thay vi gui du lieu tho bao.
private const val BUFFERED_AMOUNT_HIGH_WATERMARK = 1L * 1024 * 1024 // 1MB: tam dung khi vuot qua
private const val BUFFERED_AMOUNT_LOW_WATERMARK = 256L * 1024        // 256KB: gui tiep khi da xuong duoi
private const val DATA_CHANNEL_LABEL = "filetransfer"

// Heartbeat qua DataChannel: connectionState/readyState cua WebRTC co the KHONG chuyen
// sang disconnected/failed dung luc du duong truyen thuc te da "treo" (hay gap khi doi
// mang di dong wifi<->4G, NAT/CGNAT timeout ngam) - luc do ca 2 phia deu khong biet de tu
// noi lai. Gui 1 goi tin nho dinh ky de phia may tinh co cach xac nhan "du lieu THUC SU con
// chay qua" thay vi chi dua vao trang thai bao cao boi API.
private const val HEARTBEAT_INTERVAL_MS = 5000L

/** ICE servers dùng STUN công khai của Google + TURN dự phòng nếu 2 máy khác mạng LAN. */
private val ICE_SERVERS = listOf(
    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
    PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
    PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
    // TURN dự phòng — bắt buộc khi 2 máy ở sau CGNAT (mạng di động 4G/5G thường gặp),
    // vì lúc đó STUN không đủ để tìm đường kết nối trực tiếp, cần relay qua TURN.
    // Đây là TURN công khai miễn phí (Open Relay Project - metered.ca) dùng để demo/test;
    // triển khai thật lâu dài nên tự dựng coturn hoặc dùng dịch vụ TURN trả phí ổn định hơn.
    PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
        .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
    PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
        .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
    PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
        .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
)

/**
 * Bọc toàn bộ WebRTC PeerConnection lifecycle.
 *
 * [isHost] = true  → Máy B: tạo video track từ ScreenCapture rồi gửi offer
 * [isHost] = false → Máy A: nhận video track rồi render lên [remoteSink]
 */
class PeerConnectionManager(
    val factory: PeerConnectionFactory,
    private val isHost: Boolean,
    private val signalingClient: SignalingClient,
    /** Null trên Máy B (không cần render video của mình), non-null trên Máy A. */
    private val remoteSink: VideoSink? = null,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {}
) {
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    // Kenh gui tep/anh - TACH BIET hoan toan voi video track o tren, dung song
    // song (khong thay the) theo dung yeu cau giu ca 2 tinh nang. Luon do BEN
    // NAY (May B - dien thoai) tao ra trong init(), bat ke isHost la gi, vi
    // dien thoai luon la ben CHU DONG gui tep/anh.
    private var dataChannel: DataChannel? = null
    private var onDataChannelOpen: () -> Unit = {}
    /** Dung de sendFile() cho (Object.wait) toi khi onBufferedAmountChange() bao buffer da
     *  giam xuong duoi muc thap - xem BUFFERED_AMOUNT_*_WATERMARK o tren. */
    private val bufferedAmountLock = Object()

    // Heartbeat - xem ghi chu o HEARTBEAT_INTERVAL_MS. Chi dung o phia isHost (dien thoai),
    // vi day la ben tao dataChannel.
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null

    private fun startHeartbeat() {
        stopHeartbeat()
        val runnable = object : Runnable {
            override fun run() {
                val channel = dataChannel
                if (channel != null && channel.state() == DataChannel.State.OPEN) {
                    try {
                        val ping = org.json.JSONObject().apply { put("type", "ping") }
                        channel.send(DataChannel.Buffer(
                            java.nio.ByteBuffer.wrap(ping.toString().toByteArray()), false
                        ))
                    } catch (e: Exception) { /* kenh vua dong giua chung - bo qua, se tu dung o lan sau */ }
                    heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
                }
            }
        }
        heartbeatRunnable = runnable
        heartbeatHandler.postDelayed(runnable, HEARTBEAT_INTERVAL_MS)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    /** Track video nhận được từ phía bên kia (chỉ có ý nghĩa khi [isHost] = false). */
    fun remoteVideoTrackOrNull(): VideoTrack? = remoteVideoTrack

    companion object {
        /**
         * Tạo 1 PeerConnectionFactory dùng chung cho nhiều PeerConnectionManager (vd. khi Máy A
         * kết nối nhiều Máy Camera cùng lúc) — tránh khởi tạo lại native encoder/decoder nhiều lần.
         * Gọi 1 lần, tự dispose ở nơi gọi khi không cần nữa.
         */
        fun createFactory(context: Context, eglBase: EglBase): PeerConnectionFactory {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            val videoEncoderFactory: VideoEncoderFactory =
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            val videoDecoderFactory: VideoDecoderFactory =
                DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            return PeerConnectionFactory.builder()
                .setVideoEncoderFactory(videoEncoderFactory)
                .setVideoDecoderFactory(videoDecoderFactory)
                .createPeerConnectionFactory()
        }
    }

    /** Khởi tạo PeerConnection và bắt đầu lắng nghe signaling. */
    fun init() {
        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                signalingClient.sendIceCandidate(
                    candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp
                )
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "PeerConnection state: $newState")
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        signalingClient.markConnected()
                        onConnected()
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.FAILED -> onDisconnected()
                    else -> {}
                }
            }

            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                // Máy A nhận video track từ Máy B
                val track = transceiver?.receiver?.track() ?: return
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    if (remoteSink != null) track.addSink(remoteSink)
                }
            }

            // Stub callbacks bắt buộc
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        }) ?: throw IllegalStateException("Không tạo được PeerConnection — kiểm tra ICE server")

        // Cài sẵn transceiver để nhận video từ Máy B (cần trước khi tạo offer/answer)
        if (!isHost) {
            peerConnection?.addTransceiver(
                org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                org.webrtc.RtpTransceiver.RtpTransceiverInit(
                    org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                )
            )
        }

        // Kenh gui tep/anh - THEM VAO SONG SONG voi video track, khong thay
        // the. May B (dien thoai, isHost=true) luon la ben tao kenh nay, vi
        // no luon la ben chu dong gui tep/anh sang may tinh. Tao TRUOC khi
        // goi createOffer() de kenh nay duoc dua vao chinh phien dam phan
        // SDP dau tien (khong can dam phan lai/renegotiate ve sau).
        if (isHost) {
            val init = DataChannel.Init().apply { ordered = true }
            dataChannel = peerConnection?.createDataChannel(DATA_CHANNEL_LABEL, init)
            // Bao thuc dong bo (Object.notify) moi khi bufferedAmount() giam xuong - dung de
            // sendFile() cho HIEU QUA (khong busy-poll ton CPU) thay vi Thread.sleep() lap lai.
            dataChannel?.registerObserver(object : DataChannel.Observer {
                override fun onStateChange() {
                    Log.d(TAG, "DataChannel state: ${dataChannel?.state()}")
                    when (dataChannel?.state()) {
                        DataChannel.State.OPEN -> { onDataChannelOpen(); startHeartbeat() }
                        DataChannel.State.CLOSING, DataChannel.State.CLOSED -> stopHeartbeat()
                        else -> {}
                    }
                }
                override fun onMessage(buffer: DataChannel.Buffer) {}
                override fun onBufferedAmountChange(previousAmount: Long) {
                    // Luu y: tham so la luong TRUOC do (previousAmount), khong phai luong hien
                    // tai - phai tu doc lai dataChannel?.bufferedAmount() de biet luong THUC TE
                    // bay gio con lai bao nhieu.
                    if ((dataChannel?.bufferedAmount() ?: 0L) <= BUFFERED_AMOUNT_LOW_WATERMARK) {
                        synchronized(bufferedAmountLock) { bufferedAmountLock.notifyAll() }
                    }
                }
            })
        }

        signalingClient.start()
    }

    /**
     * Thêm video track từ ScreenCapture vào PeerConnection rồi gửi offer.
     * Chỉ gọi trên Máy B sau khi đã có [videoSource].
     */
    fun addVideoTrackAndOffer(videoSource: VideoSource) {
        localVideoTrack = factory.createVideoTrack("screen_track", videoSource)
        val sender = peerConnection?.addTrack(localVideoTrack!!, listOf("screen_stream"))
        sender?.let { configureVideoEncoding(it) }
        createAndSendOffer()
    }

    /**
     * Gửi offer CHỈ với DataChannel để truyền tệp/ảnh - KHÔNG kèm video track. Dùng cho
     * app điện thoại này (chỉ chụp & gửi tệp theo yêu cầu, không stream camera liên tục) -
     * thay cho addVideoTrackAndOffer() ở trên (giữ lại hàm đó cho mục đích khác nếu cần).
     */
    fun startFileTransferOffer() {
        createAndSendOffer()
    }

    /**
     * Giới hạn bitrate tối đa cho track video màn hình. Không set thì WebRTC có thể ước lượng
     * bitrate ban đầu quá cao so với thực tế mạng di động/TURN relay, gây nghẽn hàng đợi gửi
     * và làm hình ảnh về Máy A bị lag/khựng thay vì hạ chất lượng mượt mà theo băng thông.
     * MAINTAIN_FRAMERATE: khi băng thông không đủ, ưu tiên giữ tốc độ khung hình (đỡ giật/lag,
     * đúng nhu cầu điều khiển từ xa) và chấp nhận giảm độ phân giải trước.
     */
    private fun configureVideoEncoding(sender: org.webrtc.RtpSender) {
        val params = sender.parameters
        if (params.encodings.isNotEmpty()) {
            val encoding = params.encodings[0]
            encoding.maxBitrateBps = MAX_VIDEO_BITRATE_BPS
            encoding.minBitrateBps = MIN_VIDEO_BITRATE_BPS
        }
        params.degradationPreference = org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        sender.parameters = params
    }

    /** Nhận offer từ Máy B, set remote description rồi tạo answer. */
    fun handleOffer(sdp: String) {
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(simpleSdpObserver("setRemoteDesc(offer)"), sessionDescription)
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(answer: SessionDescription) {
                peerConnection?.setLocalDescription(simpleSdpObserver("setLocalDesc(answer)"), answer)
                signalingClient.sendAnswer(answer.description)
            }
            override fun onCreateFailure(err: String?) { Log.e(TAG, "createAnswer fail: $err") }
            override fun onSetSuccess() {}
            override fun onSetFailure(err: String?) {}
        }, MediaConstraints())
    }

    /** Nhận answer từ Máy A, set remote description. */
    fun handleAnswer(sdp: String) {
        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(simpleSdpObserver("setRemoteDesc(answer)"), sessionDescription)
    }

    fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    /**
     * Gui 1 tep/anh sang may tinh qua DataChannel, chia thanh tung doan nho.
     *
     * CHI cho phep truyen khi CA HAI dang ket noi thanh cong (PeerConnection
     * o trang thai CONNECTED va DataChannel dang OPEN) - dung yeu cau "chi
     * cho phep truyen khi ca 2 dang ket noi thanh cong voi nhau". Neu mat
     * ket noi GIUA CHUNG luc dang gui (kiem tra lai truoc MOI doan), HUY
     * NGAY - gui thong diep "file-cancel" bao may tinh xoa phan da nhan do,
     * khong co gang gui tiep/tu noi lai giua chung.
     *
     * @param onProgress goi lai sau moi doan da gui (0..100)
     * @param onResult goi lai 1 lan duy nhat khi xong: true = gui du va thanh
     *   cong, false = bi huy (mat ket noi giua chung) hoac loi
     */
    fun sendFile(
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String,
        onProgress: (Int) -> Unit = {},
        onResult: (Boolean) -> Unit = {}
    ) {
        val channel = dataChannel
        val pc = peerConnection
        if (channel == null || pc == null ||
            pc.connectionState() != PeerConnection.PeerConnectionState.CONNECTED ||
            channel.state() != DataChannel.State.OPEN
        ) {
            Log.w(TAG, "sendFile: chua ket noi xong (pc=${pc?.connectionState()}, dc=${channel?.state()}) - huy")
            onResult(false)
            return
        }

        val transferId = System.currentTimeMillis().toString() + "_" + (0..9999).random()
        val totalChunks = if (fileBytes.isEmpty()) 0 else (fileBytes.size + FILE_CHUNK_SIZE - 1) / FILE_CHUNK_SIZE

        val header = org.json.JSONObject().apply {
            put("type", "file-start")
            put("transferId", transferId)
            put("name", fileName)
            put("mimeType", mimeType)
            put("size", fileBytes.size)
            put("totalChunks", totalChunks)
        }
        channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(header.toString().toByteArray()), false))

        var offset = 0
        var chunkIndex = 0
        while (offset < fileBytes.size) {
            // Kiem tra lai TRUOC MOI doan - mat ket noi giua chung la huy ngay,
            // khong co bat ky lan thu lai/noi tiep nao.
            if (pc.connectionState() != PeerConnection.PeerConnectionState.CONNECTED ||
                channel.state() != DataChannel.State.OPEN
            ) {
                Log.w(TAG, "sendFile: mat ket noi giua chung o doan $chunkIndex/$totalChunks - huy")
                val cancelMsg = org.json.JSONObject().apply {
                    put("type", "file-cancel"); put("transferId", transferId)
                }
                try {
                    channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(cancelMsg.toString().toByteArray()), false))
                } catch (e: Exception) { /* ket noi da chet han, khong gui duoc nua cung khong sao */ }
                onResult(false)
                return
            }
            // Kiem soat luong: neu kenh dang con qua nhieu du lieu CHUA GUI DI THUC SU (buffer
            // noi bo cao hon muc cao), TAM DUNG cho toi khi no xuong duoi muc thap roi moi gui
            // tiep - tranh phinh bo dem gay dung/rot ket noi, giu toc do gui bam sat bang thong
            // THUC TE (nhanh nhat co the an toan), tuong tu cach mot luong video tu dieu tiet.
            if (channel.bufferedAmount() > BUFFERED_AMOUNT_HIGH_WATERMARK) {
                synchronized(bufferedAmountLock) {
                    while (channel.bufferedAmount() > BUFFERED_AMOUNT_HIGH_WATERMARK &&
                        pc.connectionState() == PeerConnection.PeerConnectionState.CONNECTED &&
                        channel.state() == DataChannel.State.OPEN
                    ) {
                        bufferedAmountLock.wait(200L)
                    }
                }
                continue
            }
            val end = minOf(offset + FILE_CHUNK_SIZE, fileBytes.size)
            val chunk = fileBytes.copyOfRange(offset, end)
            channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(chunk), true))
            offset = end
            chunkIndex++
            onProgress((chunkIndex * 100) / totalChunks)
        }

        val endMsg = org.json.JSONObject().apply {
            put("type", "file-end"); put("transferId", transferId)
        }
        channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(endMsg.toString().toByteArray()), false))
        onResult(true)
    }

    /** true neu ca PeerConnection va DataChannel deu dang san sang de gui tep. */
    fun isReadyToSendFile(): Boolean {
        return peerConnection?.connectionState() == PeerConnection.PeerConnectionState.CONNECTED &&
            dataChannel?.state() == DataChannel.State.OPEN
    }

    private fun createAndSendOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(offer: SessionDescription) {
                peerConnection?.setLocalDescription(simpleSdpObserver("setLocalDesc(offer)"), offer)
                signalingClient.sendOffer(offer.description)
            }
            override fun onCreateFailure(err: String?) { Log.e(TAG, "createOffer fail: $err") }
            override fun onSetSuccess() {}
            override fun onSetFailure(err: String?) {}
        }, constraints)
    }

    private fun simpleSdpObserver(tag: String) = object : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() { Log.d(TAG, "$tag onSetSuccess") }
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(err: String?) { Log.e(TAG, "$tag onSetFailure: $err") }
    }

    fun release() {
        stopHeartbeat()
        localVideoTrack?.dispose()
        dataChannel?.close()
        dataChannel?.dispose()
        dataChannel = null
        peerConnection?.close()
        peerConnection?.dispose()
        // KHÔNG dispose `factory` ở đây nữa — factory dùng chung, nơi tạo ra nó (Service)
        // chịu trách nhiệm dispose khi không còn phiên nào dùng tới.
    }
}
