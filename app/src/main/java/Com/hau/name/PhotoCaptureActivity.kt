package Com.hau.name

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.constraintlayout.widget.ConstraintLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Nen + giam kich thuoc anh truoc khi gui - dung CUNG do phan giai voi luong video
// (xem README: stream video quay 1280x720) va nen JPEG o muc chat luong tuong duong
// video (video bi gioi han MAX_VIDEO_BITRATE_BPS = 2Mbps cho 1280x720@20fps trong
// PeerConnectionManager.kt - ty le nen rat cao). Anh chup tu CameraX o do phan giai
// cam bien day du (thuong vai MB, doi may 10-50MP) du chi de xem tren man hinh may
// tinh - giam ve cung muc voi video giup gui/nhan nhanh hon nhieu lan ma van du net.
private const val PHOTO_SEND_MAX_LONG_EDGE = 1280
private const val PHOTO_SEND_JPEG_QUALITY = 80

/**
 * Man hinh Chup anh (mo tu nut "📷 Chup anh" tren CameraActivity).
 *
 * - Khung camera lon (CameraX preview, camera SAU mac dinh)
 * - Nut chup o giua-duoi (vien tron xam day bong)
 * - Nut xem anh vua chup (ben trai nut chup) - CHI hien sau khi chup it
 *   nhat 1 tam trong phien nay, mac dinh an
 * - Nut bat/tat flash (gan canh phai man hinh)
 * - KHONG con nut thoat rieng (goc tren-trai) - bam Back (nut he thong) se THOAT HAN
 *   app va NGAT KET NOI luon (xem onBackPressed / exitAppAndDisconnect), thay vi chi
 *   dua app xuong nen nhu thiet ke cu.
 * - Man hinh (khung ngam + man xem lai) luon nam o doan giua man hinh, khong con
 *   tran vien nhu truoc.
 * - Khi chup: CHI luu anh xuong may (MediaStore, thu muc Pictures/QrTruyenTep) -
 *   KHONG tu dong gui qua may tinh nua. Anh vua chup hien ngay o doan giua man hinh
 *   (layout_photo_review) de xem lai truoc - KHONG con nut X:
 *     + Cham 1 lan vao anh (hoac bam Back) -> dong, KHONG gui, quay lai khung ngam.
 *     + Vuot 2 ngon tay tu duoi len tren TREN CHINH TAM ANH -> anh "bay" theo
 *       ngon tay len tren (hieu ung keo theo thoi gian thuc), tha tay khi da
 *       vuot qua nguong -> anh bay tiep len va bien mat, ĐONG THOI gui that
 *       sang may tinh (sendPhotoToComputer) - giong nhu dang "day" anh qua
 *       may tinh. Neu tha tay ma chua du nguong, anh tu troi lai vi tri cu.
 */
class PhotoCaptureActivity : AppCompatActivity() {

    private var roomCode: String? = null

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: View
    private lateinit var btnFlash: ImageView
    private lateinit var btnSwitchCamera: ImageView
    private lateinit var btnViewLastPhoto: ImageView
    private lateinit var layoutTopControls: View
    private lateinit var bottomControls: View
    private lateinit var textPairingCodeChip: TextView
    private lateinit var btnSendFile: android.widget.Button
    private lateinit var layoutConnectionStatus: View
    private lateinit var dotConnectionStatus: View
    private lateinit var textConnectionStatus: TextView

    private lateinit var layoutPhotoReview: FrameLayout
    private lateinit var imageReviewPhoto: ImageView
    private lateinit var textReviewHint: TextView

    private var textSync: TextSyncController? = null
    private var connectedNow = false

    /** Ghi ngay gio len goc tren-trai anh chup (nho lai qua lan mo sau). */
    private var timestampOn = false
    private lateinit var btnTimestamp: ImageView
    private lateinit var textTimestampOverlay: TextView
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val clockRunnable = object : Runnable {
        override fun run() {
            if (!timestampOn) return
            textTimestampOverlay.text = clockFormat.format(Date())
            clockHandler.postDelayed(this, 1000L)
        }
    }

    /** Camera dang TAT (giu nut chup 3s de tat, giu 2s de mo lai). */
    private var cameraOff = false
    private lateinit var textCameraOff: TextView
    private val holdHandler = Handler(Looper.getMainLooper())
    private var holdTriggered = false
    private var holdStartTime = 0L
    private val holdToTurnOffMs = 3000L
    private val holdToTurnOnMs = 2000L
    private val holdRunnable = Runnable {
        holdTriggered = true
        btnCapture.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        setCameraOff(!cameraOff)
    }

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var flashOn = false
    private var lastPhotoUri: Uri? = null
    /** Camera dang dung - mac dinh camera SAU, doi qua lai khi bam [btnSwitchCamera]. */
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var cameraProvider: ProcessCameraProvider? = null

    /** Bat zoom bang cach chum/xoe 2 ngon tay (pinch-to-zoom) ngay tren khung ngam camera -
     *  gan lai moi khi startCamera() chay (vd sau khi doi camera truoc/sau) vi doi tuong
     *  [camera] (CameraX Camera) thay doi theo tung lan bindToLifecycle. */
    private var scaleGestureDetector: ScaleGestureDetector? = null

    /** Vi tri ngon tay khi cham xuong man hinh xem lai anh (1 ngon) - dung de phan biet
     *  "cham 1 lan de dong" voi bat dau vuot 2 ngon de gui. */
    private var singleTapStartX = 0f
    private var singleTapStartY = 0f
    private val tapSlopPx: Float by lazy { resources.displayMetrics.density * 12f }

    /** Quang duong (px) toi thieu phai vuot 2 ngon len tren de tinh la "gui" - duoi muc nay
     *  thi tha tay se troi anh lai vi tri cu, khong gui. */
    private val swipeSendThresholdPx: Float by lazy { resources.displayMetrics.density * 130f }

    private var reviewDragStartY: Float? = null
    private var reviewDragging = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Cần quyền Camera để chụp ảnh", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Man hinh chup anh phong to CHIEM TRON man hinh (ke ca vung sau status
        // bar) - vo hieu hoa viec he thong tu chua noi dung lai, roi tu tay cong
        // them padding = chieu cao status/nav bar cho CAC NUT dieu khien phia tren
        // (setupEdgeToEdgeInsets) de chung khong bi che boi dong ho/pin, con khung
        // camera thi van tran het toan bo man hinh.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        setContentView(R.layout.activity_photo_capture)
        roomCode = intent.getStringExtra(EXTRA_ROOM_CODE)

        rootLayout = findViewById(R.id.root_photo_capture)
        previewView = findViewById(R.id.camera_preview)
        btnCapture = findViewById(R.id.btn_capture)
        btnFlash = findViewById(R.id.btn_toggle_flash)
        btnSwitchCamera = findViewById(R.id.btn_switch_camera)
        btnViewLastPhoto = findViewById(R.id.btn_view_last_photo)
        layoutTopControls = findViewById(R.id.layout_top_controls)
        bottomControls = findViewById(R.id.bottom_controls)
        textPairingCodeChip = findViewById(R.id.text_pairing_code_chip)
        btnSendFile = findViewById(R.id.btn_send_file)
        layoutConnectionStatus = findViewById(R.id.layout_connection_status)
        dotConnectionStatus = findViewById(R.id.dot_connection_status)
        textConnectionStatus = findViewById(R.id.text_connection_status)
        renderConnectionStatus(0, MAX_VIEWERS_PER_CAMERA)

        setupEdgeToEdgeInsets()

        layoutPhotoReview = findViewById(R.id.layout_photo_review)
        imageReviewPhoto = findViewById(R.id.image_review_photo)
        textReviewHint = findViewById(R.id.text_review_hint)

        textPairingCodeChip.text = getString(R.string.pairing_code_chip_format, roomCode ?: "------")
        // Chua ket noi thi bam vao o "Ma" de TU NHAP ma moi (6 so), luu lai lam ma co dinh.
        textPairingCodeChip.setOnClickListener {
            if (connectedNow) return@setOnClickListener
            showChangeCodeDialog()
        }

        // Man hinh nay gio la man hinh chinh duy nhat cua app (thay cho CameraActivity
        // truoc day). KHONG con nut thoat rieng - bam Back (nut he thong) se thoat han
        // + ngat ket noi (xem onBackPressed / exitAppAndDisconnect).
        textCameraOff = findViewById(R.id.text_camera_off)
        // Nho trang thai TAT camera qua lan thoat/vao lai app (ke ca khi he thong tat han app).
        cameraOff = getSharedPreferences("truyentep", MODE_PRIVATE).getBoolean("camera_off", false)
        if (cameraOff) {
            previewView.visibility = View.INVISIBLE
            textCameraOff.visibility = View.VISIBLE
        }
        btnTimestamp = findViewById(R.id.btn_timestamp)
        textTimestampOverlay = findViewById(R.id.text_timestamp_overlay)
        timestampOn = getSharedPreferences("truyentep", MODE_PRIVATE).getBoolean("timestamp_on", false)
        updateTimestampButton()
        btnTimestamp.setOnClickListener {
            timestampOn = !timestampOn
            getSharedPreferences("truyentep", MODE_PRIVATE).edit().putBoolean("timestamp_on", timestampOn).apply()
            updateTimestampButton()
            Toast.makeText(
                this,
                if (timestampOn) "Ghi ngày giờ lên ảnh: BẬT" else "Ghi ngày giờ lên ảnh: TẮT",
                Toast.LENGTH_SHORT
            ).show()
        }
        setupCaptureButtonHold()
        btnFlash.setOnClickListener { toggleFlash() }
        btnSwitchCamera.setOnClickListener { toggleCamera() }
        btnViewLastPhoto.setOnClickListener { openLastPhotoViewer() }
        textSync = TextSyncController(
            this,
            findViewById<EditText>(R.id.edit_input),
            findViewById<TextView>(R.id.text_input_status),
            findViewById<Button>(R.id.btn_paste),
            findViewById<Button>(R.id.btn_clear_box)
        )
        textSync?.setConnected(connectedNow)
        btnSendFile.setOnClickListener {
            // Chi vao trang Gui tep khi DANG ket noi may tinh.
            if (!connectedNow) {
                Toast.makeText(this, "Cần kết nối máy tính trước khi gửi tệp", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, SendFileActivity::class.java).apply {
                putExtra(SendFileActivity.EXTRA_ROOM_CODE, roomCode)
            })
        }
        setupSwipeUpToSend()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** Hop thoai cho nguoi dung tu nhap ma ghep noi moi (dung 6 chu so). */
    private fun showChangeCodeDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            hint = "6 chữ số"
            setText(roomCode ?: "")
            setSelectAllOnFocus(true)
            gravity = android.view.Gravity.CENTER
            textSize = 22f
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Nhập mã mới")
            .setView(box)
            .setPositiveButton("Đổi mã", null)
            .setNegativeButton("Huỷ", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val newCode = input.text.toString().trim()
                if (!Regex("\\d{6}").matches(newCode)) {
                    Toast.makeText(this, "Mã phải đủ 6 chữ số", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (connectedNow) { dialog.dismiss(); return@setOnClickListener }
                applyNewCode(newCode)
                dialog.dismiss()
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
    }

    private fun applyNewCode(newCode: String) {
        if (newCode == roomCode) return
        val service = CameraStreamService.instance
        if (service == null) {
            Toast.makeText(this, "Dịch vụ chưa sẵn sàng, thử lại sau giây lát", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences(CameraActivity.PREFS_NAME, MODE_PRIVATE).edit()
            .putString(CameraActivity.KEY_FIXED_CODE, newCode).apply()
        roomCode = newCode
        service.changeRoomCode(newCode)
        textPairingCodeChip.text = getString(R.string.pairing_code_chip_format, newCode)
        Toast.makeText(this, "Đã đổi sang mã mới: $newCode", Toast.LENGTH_SHORT).show()
    }

    /** Cong them padding/margin = chieu cao status bar (tren) va navigation bar (duoi) cho
     *  hang nut phia tren (chip ma, nut Gui tep) va hang nut phia duoi (chup/flash/xem anh) -
     *  de cac nut nay khong bi status bar (dong ho, pin...) hay navigation bar de len, trong
     *  khi khung camera (camera_preview) van tran het toan bo man hinh phia sau. */
    private fun setupEdgeToEdgeInsets() {
        val baseTopMargin = (8 * resources.displayMetrics.density).toInt()
        val panel = findViewById<View>(R.id.panel_text_input)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            layoutTopControls.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = baseTopMargin + bars.top
            }
            // Khung nhap chu nam sat day; khi ban phim hien thi thi day len tren ban phim
            // (khung chup tu thu nho lai), khi khong co ban phim thi tru chieu cao thanh dieu huong.
            panel.updateLayoutParams<ConstraintLayout.LayoutParams> {
                bottomMargin = maxOf(bars.bottom, ime.bottom)
            }
            insets
        }
    }

    /**
     * Cap nhat cham tron + chu o goc tren-trai theo trang thai ket noi hien tai. Mau xanh la
     * (#4CAF50) khi co it nhat 1 may tinh dang ket noi thanh cong, mau xam (#9E9E9E) khi chua
     * co may nao - giup nguoi dung biet NGAY co the vuot-de-gui anh duoc hay chua, khong can
     * doan qua thong bao he thong.
     */
    private fun renderConnectionStatus(connectedCount: Int, maxViewers: Int) {
        val connected = connectedCount > 0
        connectedNow = connected
        btnSendFile.alpha = if (connected) 1f else 0.4f
        textSync?.setConnected(connected)
        // Da ket noi thi AN ma 6 so (khong can nua); mat ket noi thi hien lai.
        textPairingCodeChip.visibility = if (connected) View.GONE else View.VISIBLE
        val color = if (connected) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt()
        (dotConnectionStatus.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
            ?: dotConnectionStatus.background?.setTint(color)
        textConnectionStatus.text = if (connected) {
            getString(R.string.connection_status_connected, connectedCount, maxViewers)
        } else {
            getString(R.string.connection_status_disconnected)
        }
    }

    private val connectionStatusListener = object : CameraStreamService.ConnectionStatusListener {
        override fun onConnectionStatusChanged(connectedCount: Int, maxViewers: Int) {
            runOnUiThread { renderConnectionStatus(connectedCount, maxViewers) }
        }
    }

    private val statusListenerHandler = Handler(Looper.getMainLooper())
    /** CameraActivity vua goi startForegroundService() ngay TRUOC KHI mo man hinh nay - Service
     *  co the chua kip tao xong (instance con null trong choc lat) do he thong xu ly bat dong
     *  bo. Thu lai vai lan cach nhau ngan de dang ky duoc NGAY KHI service san sang, thay vi bo
     *  qua han va khien goc tren-trai ket ket qua "Chưa kết nối" mai du service da chay. */
    private val registerStatusListenerRunnable = object : Runnable {
        override fun run() {
            val service = CameraStreamService.instance
            if (service != null) {
                service.addConnectionStatusListener(connectionStatusListener)
            } else {
                statusListenerHandler.postDelayed(this, 300L)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Dang ky NGAY khi man hinh hien ra de goc tren-trai luon phan anh dung trang thai -
        // service co the da chay tu truoc (vd. quay lai man hinh nay tu Recents).
        statusListenerHandler.post(registerStatusListenerRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        holdHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacksAndMessages(null)
        textSync?.release()
        textSync = null
    }

    override fun onStop() {
        super.onStop()
        statusListenerHandler.removeCallbacks(registerStatusListenerRunnable)
        CameraStreamService.instance?.removeConnectionStatusListener(connectionStatusListener)
    }

    /** Nut chup: bam NHANH = chup anh. Giu 3 giay (khi camera dang bat) = TAT camera; giu 2 giay
     *  (khi camera dang tat) = MO lai camera. Giu giua chung (qua lau de la bam nhanh nhung chua
     *  du gio) thi khong lam gi. Tha tay truoc khi du gio -> huy, khong tat/mo. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupCaptureButtonHold() {
        btnCapture.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    holdTriggered = false
                    holdStartTime = android.os.SystemClock.uptimeMillis()
                    holdHandler.postDelayed(
                        holdRunnable,
                        if (cameraOff) holdToTurnOnMs else holdToTurnOffMs
                    )
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    holdHandler.removeCallbacks(holdRunnable)
                    val held = android.os.SystemClock.uptimeMillis() - holdStartTime
                    if (!holdTriggered) {
                        if (cameraOff) {
                            Toast.makeText(this, "Camera đang tắt - giữ nút chụp 2 giây để mở lại", Toast.LENGTH_SHORT).show()
                        } else if (held < 600L) {
                            takePhoto()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    holdHandler.removeCallbacks(holdRunnable)
                    true
                }
                else -> true
            }
        }
    }

    /** TAT: nha camera (unbind) va an khung ngam; MO: bind lai camera nhu luc moi vao. */
    private fun setCameraOff(off: Boolean) {
        cameraOff = off
        getSharedPreferences("truyentep", MODE_PRIVATE).edit().putBoolean("camera_off", off).apply()
        if (off) {
            if (flashOn) {
                flashOn = false
                updateFlashButtonIcon()
            }
            cameraProvider?.unbindAll()
            camera = null
            imageCapture = null
            previewView.visibility = View.INVISIBLE
            textTimestampOverlay.visibility = View.GONE
            textCameraOff.visibility = View.VISIBLE
            Toast.makeText(this, "Đã tắt camera", Toast.LENGTH_SHORT).show()
        } else {
            textCameraOff.visibility = View.GONE
            previewView.visibility = View.VISIBLE
            if (timestampOn) textTimestampOverlay.visibility = View.VISIBLE
            startCamera()
            Toast.makeText(this, "Đã mở camera", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCamera() {
        if (cameraOff) return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setFlashMode(if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                // Uu tien toc do chup hon chat luong toi da (mac dinh CAMERAX la
                // CAPTURE_MODE_MAXIMIZE_QUALITY - xu ly anh ky/lau hon) -> chup
                // nhanh hon ro ret, dac biet tren may trung binh/thap.
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, currentCameraSelector, preview, imageCapture
                )
                // Neu nguoi dung da bat den truoc khi camera san sang, ap dung lai ngay.
                // Camera TRUOC thuong khong co den flash - enableTorch() se tu that bai
                // ngay ben trong (da kiem tra hasFlashUnit() truoc do trong toggleFlash()).
                if (flashOn) camera?.cameraControl?.enableTorch(true)
                setupPinchToZoom()
            } catch (e: Exception) {
                Toast.makeText(this, "Không mở được camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Chum/xoe 2 ngon tay tren khung ngam de zoom vao/ra - phai goi lai moi lan startCamera()
     *  chay (kem [camera] moi) vi zoom la thuoc tinh cua object CameraX Camera hien tai, doi
     *  camera truoc/sau se tao object [camera] moi hoan toan. Dung setZoomRatio (ty le zoom
     *  that, vd 1x/2x/3x) thay vi setLinearZoom vi de hieu va khop voi thong so hien thi neu
     *  sau nay can them chu "x" tren man hinh. Ty le tang/giam theo scaleFactor cua chinh cu
     *  chi chum tay (khong nhay cam theo toc do vuot tay) va bi gioi han trong khoang
     *  minZoomRatio..maxZoomRatio thuc te cua tung may/tung ong kinh (CameraX tu bao qua
     *  ZoomState, co may chi toi ~4x-8x, co may toi vai chuc x). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupPinchToZoom() {
        if (camera == null) return
        if (scaleGestureDetector == null) {
            scaleGestureDetector = ScaleGestureDetector(
                this,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val currentCamera = camera ?: return false
                        val zoomState = currentCamera.cameraInfo.zoomState.value ?: return false
                        val newRatio = (zoomState.zoomRatio * detector.scaleFactor)
                            .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                        currentCamera.cameraControl.setZoomRatio(newRatio)
                        return true
                    }
                }
            )
            previewView.setOnTouchListener { _, event ->
                scaleGestureDetector?.onTouchEvent(event)
                true
            }
        }
    }

    /** Doi qua lai camera truoc/sau - bam nut o vi tri cu cua nut flash (xem
     *  activity_photo_capture.xml). Kiem tra may co camera do khong truoc khi doi
     *  (mot so may chi co 1 camera) de tranh crash; neu khong co thi bao va giu nguyen. */
    private fun toggleCamera() {
        if (cameraOff) return
        val provider = cameraProvider ?: return
        val nextSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        if (!provider.hasCamera(nextSelector)) {
            Toast.makeText(this, "Máy này không có camera còn lại để đổi", Toast.LENGTH_SHORT).show()
            return
        }
        // Doi camera thi tat den flash truoc (camera moi co the khong co den, hoac
        // dang bat den camera cu dang truyen sang camera moi se gay nham lan) -
        // nguoi dung tu bat lai neu can va camera moi ho tro.
        if (flashOn) {
            flashOn = false
            updateFlashButtonIcon()
        }
        currentCameraSelector = nextSelector
        startCamera()
    }

    /** Bat/tat den flash NGAY LAP TUC (giong den pin, bang CameraControl.enableTorch) - trước
     *  đây chỉ đổi ImageCapture.flashMode nên đèn chỉ loé lên đúng lúc bấm chụp, không sáng
     *  liên tục khi bấm nút này, khiến người dùng tưởng nút không hoạt động.
     *  Hieu ung bat/tat ro rang tren nut: BAT -> icon tia set mau vang; TAT -> icon tia set
     *  co GACH CHEO qua, mau trang mo. */
    private fun toggleFlash() {
        if (cameraOff) return
        flashOn = !flashOn
        imageCapture?.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        val torchCamera = camera
        if (torchCamera == null || torchCamera.cameraInfo.hasFlashUnit().not()) {
            flashOn = false
            imageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
            updateFlashButtonIcon()
            Toast.makeText(this, "Máy này không có đèn flash", Toast.LENGTH_SHORT).show()
            return
        }
        torchCamera.cameraControl.enableTorch(flashOn)
        updateFlashButtonIcon()
    }

    /** Cap nhat icon nut flash theo dung [flashOn]: BAT -> ic_flash_on mau vang;
     *  TAT -> ic_flash_off (tia set + gach cheo) mau trang mo. */
    private fun updateFlashButtonIcon() {
        if (flashOn) {
            btnFlash.setImageResource(R.drawable.ic_flash_on)
            btnFlash.setColorFilter(0xFFFFD54F.toInt())
        } else {
            btnFlash.setImageResource(R.drawable.ic_flash_off)
            btnFlash.clearColorFilter()
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = "truyentep_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/QrTruyenTep")
            }
        }
        // Camera TRUOC: preview tren man hinh da tu dong lat guong (hanh vi mac dinh
        // cua CameraX Preview) nhung anh LUU RA lai KHONG lat theo mac dinh -> nguoi
        // dung thay preview 1 kieu, anh luu ra lai nguoc (chu/vat bi lat trai-phai so
        // voi nhung gi ho thay luc chup). Dat Metadata.isReversedHorizontal = true khi
        // dang dung camera truoc de anh LUU RA cung duoc lat giong preview (ImageCapture
        // KHONG co setMirrorMode() nhu VideoCapture/Preview - phai lat qua Metadata nay).
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ).setMetadata(metadata).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    lastPhotoUri = output.savedUri
                    btnViewLastPhoto.visibility = View.VISIBLE
                    // Chi luu tren may thoi - KHONG tu dong gui. Hien anh vua chup full man
                    // hinh de xem lai, chi gui khi nguoi dung chu dong vuot 2 ngon len tren.
                    // Giai ma anh o luong nen + thu nho (setImageURI tren luong UI voi anh
                    // vai chuc MP lam may treo ~1-2s, keo theo mat ket noi luc bam chup).
                    val savedUri = lastPhotoUri
                    if (savedUri != null) {
                        if (timestampOn) {
                            val takenAt = System.currentTimeMillis()
                            // Ve ngay gio len anh o luong nen, xong moi hien anh xem lai.
                            Thread {
                                stampDateTimeOnPhoto(savedUri, takenAt)
                                runOnUiThread {
                                    if (!isFinishing && !isDestroyed) loadPhotoAsync(savedUri)
                                }
                            }.start()
                        } else {
                            loadPhotoAsync(savedUri)
                        }
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(
                        this@PhotoCaptureActivity, "Chụp ảnh thất bại: ${exc.message}", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    /** BAT -> icon mau cam, TAT -> icon trang mo. */
    private fun updateTimestampButton() {
        clockHandler.removeCallbacks(clockRunnable)
        if (timestampOn) {
            textTimestampOverlay.visibility = if (cameraOff) View.GONE else View.VISIBLE
            clockHandler.post(clockRunnable)
        } else {
            textTimestampOverlay.visibility = View.GONE
        }
        if (timestampOn) {
            btnTimestamp.setColorFilter(0xFFFF6B00.toInt())
            btnTimestamp.alpha = 1f
        } else {
            btnTimestamp.clearColorFilter()
            btnTimestamp.alpha = 0.55f
        }
    }

    /** Ve "yyyy-MM-dd HH:mm:ss" vao goc TREN-TRAI anh da luu (theo chieu anh khi nhin dung) roi
     *  ghi de lai file. Chay o luong nen. Doc EXIF de xoay/lat dung chieu truoc khi ve (file ghi
     *  lai khong con EXIF xoay). Loi thi giu nguyen anh goc. */
    private fun stampDateTimeOnPhoto(uri: Uri, takenAt: Long) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
            var sample = 1
            while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > 16_000_000L) sample *= 2
            val decoded = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return

            val orientation = try {
                contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                else -> {}
            }
            var bmp = if (m.isIdentity) decoded else
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            if (!bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true) ?: return

            val text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(takenAt))
            val shortEdge = minOf(bmp.width, bmp.height).toFloat()
            val textSize = shortEdge * 0.04f
            val margin = shortEdge * 0.025f
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = textSize
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            }
            val x = margin
            val y = margin + textSize
            // Vien den cho de doc tren moi nen, roi chu cam len tren.
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = textSize * 0.14f
            paint.color = android.graphics.Color.BLACK
            canvas.drawText(text, x, y, paint)
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = 0xFFFF9800.toInt()
            canvas.drawText(text, x, y, paint)

            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(out.toByteArray()) }
        } catch (e: Throwable) {
            // Giu nguyen anh goc neu khong ve duoc (vd. het bo nho).
        }
    }

    /** Giai ma anh o luong nen voi inSampleSize (toi da ~1600px canh dai, doc EXIF xoay),
     *  roi cap nhat nut xem anh + man xem lai tren luong UI. */
    private fun loadPhotoAsync(uri: Uri) {
        Thread {
            val bmp: Bitmap? = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
                while (longEdge / (sample * 2) >= 1600) sample *= 2
                val decoded = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
                val degrees = try {
                    contentResolver.openInputStream(uri)?.use { ins ->
                        when (ExifInterface(ins).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                            else -> 0f
                        }
                    } ?: 0f
                } catch (e: Exception) { 0f }
                if (decoded != null && degrees != 0f) {
                    Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
                        Matrix().apply { postRotate(degrees) }, true)
                } else decoded
            } catch (e: Throwable) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (bmp != null) {
                    btnViewLastPhoto.setImageBitmap(bmp)
                    openPhotoReview(bmp)
                }
            }
        }.start()
    }

    /** Hien anh vua chup full man hinh, san sang cho vuot 2 ngon de gui. */
    private fun openPhotoReview(bmp: Bitmap) {
        imageReviewPhoto.setImageBitmap(bmp)
        imageReviewPhoto.translationY = 0f
        imageReviewPhoto.alpha = 1f
        textReviewHint.translationY = 0f
        textReviewHint.alpha = 1f
        layoutPhotoReview.visibility = View.VISIBLE
    }

    /** Dong man xem lai anh, KHONG gui gi ca, quay lai khung ngam camera. Khong con nut X -
     *  goi ham nay bang cach cham 1 lan vao anh (xem setupSwipeUpToSend) hoac bam Back
     *  (xem onBackPressed). */
    private fun closePhotoReview() {
        layoutPhotoReview.visibility = View.GONE
        imageReviewPhoto.translationY = 0f
        imageReviewPhoto.alpha = 1f
    }

    /** Bam Back khi dang xem lai anh -> chi dong man xem lai (khong gui), khong thoat man
     *  hinh chup anh. Bam Back luc khac (khong con nut X rieng) -> THOAT HAN app va NGAT
     *  KET NOI luon, KHAC voi thiet ke cu la chi dua app xuong nen giu webcam chay ngam. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (layoutPhotoReview.visibility == View.VISIBLE) {
            closePhotoReview()
        } else {
            exitAppAndDisconnect()
        }
    }

    /** Thoat han ung dung + ngat ket noi webcam: goi THANG (dong bo, cung tien trinh)
     *  CameraStreamService.instance?.stopSharingNow() thay vi gui Intent bat dong bo qua
     *  startService() nhu truoc - dam bao dong PeerConnection + bao Firebase "ended" CHAC
     *  CHAN hoan tat xong TRUOC KHI finishAffinity(), tranh truong hop he thong dong/kill
     *  app truoc khi Intent kip duoc xu ly (co the la nguyen nhan gay "thoat roi ma van
     *  con ket noi"). Neu service da chet san (instance null) thi van gui them Intent
     *  ACTION_STOP_SHARING de phong khi Android tu khoi dong lai service o dang "mo cu" -
     *  double-safety, khong hai gi vi cleanupSession() chan chay trung. */
    private fun exitAppAndDisconnect() {
        val service = CameraStreamService.instance
        if (service != null) {
            service.stopSharingNow()
        } else {
            startService(Intent(this, CameraStreamService::class.java).apply {
                action = CameraStreamService.ACTION_STOP_SHARING
            })
        }
        finishAffinity()
    }

    /**
     * Theo doi cu chi vuot 2 ngon tay tu duoi len tren NGAY TREN man hinh xem lai anh:
     * - 2 ngon cham xuong -> bat dau theo doi.
     * - Keo len -> anh (+ dong chu hint) di chuyen theo dung do vuot cua ngon tay (translationY
     *   am dan theo huong len), mo dan neu keo qua nua duong - cam giac dang "keo" that anh len.
     * - Tha tay:
     *     + Neu da vuot qua [swipeSendThresholdPx] -> anh bay tiep len tren va bien mat
     *       (animateFlyAwayAndSend) roi GUI THAT sang may tinh.
     *     + Chua du nguong -> troi nhe nhang ve vi tri cu (khong gui).
     * Chi vuot XUONG (ngon tay di xuong) se khong lam gi (coerceAtMost 0f chan huong nguoc).
     */
    private fun setupSwipeUpToSend() {
        layoutPhotoReview.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 1 ngon cham xuong: co the la cham-1-lan-de-dong, theo doi vi tri ban dau.
                    singleTapStartX = event.x
                    singleTapStartY = event.y
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        reviewDragStartY = averagePointerY(event)
                        reviewDragging = true
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (reviewDragging && event.pointerCount >= 2) {
                        val startY = reviewDragStartY ?: averagePointerY(event)
                        val deltaY = (averagePointerY(event) - startY).coerceAtMost(0f)
                        imageReviewPhoto.translationY = deltaY
                        textReviewHint.translationY = deltaY
                        val progress = (-deltaY / swipeSendThresholdPx).coerceIn(0f, 1f)
                        textReviewHint.alpha = 1f - progress
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (reviewDragging) {
                        reviewDragging = false
                        reviewDragStartY = null
                        if (-imageReviewPhoto.translationY >= swipeSendThresholdPx) {
                            animateFlyAwayAndSend()
                        } else {
                            animateSpringBack()
                        }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        // Cham 1 lan (khong keo, khong phai 2 ngon) -> dong lai, khong gui.
                        val movedX = kotlin.math.abs(event.x - singleTapStartX)
                        val movedY = kotlin.math.abs(event.y - singleTapStartY)
                        if (movedX < tapSlopPx && movedY < tapSlopPx) {
                            closePhotoReview()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun averagePointerY(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return sum / event.pointerCount
    }

    /** Vuot du nguong: cho anh bay tiep len tren mat man hinh + mo dan, roi gui that sang may
     *  tinh va dong man xem lai - dung hieu ung nay lam cam giac "day" anh qua may tinh. */
    private fun animateFlyAwayAndSend() {
        val flyDistance = layoutPhotoReview.height.toFloat().let { if (it > 0f) it else resources.displayMetrics.heightPixels.toFloat() }
        imageReviewPhoto.animate()
            .translationY(-flyDistance)
            .alpha(0f)
            .setDuration(240L)
            .withEndAction {
                layoutPhotoReview.visibility = View.GONE
                imageReviewPhoto.translationY = 0f
                imageReviewPhoto.alpha = 1f
                sendPhotoToComputer(lastPhotoUri)
            }
            .start()
        textReviewHint.animate().alpha(0f).setDuration(150L).start()
    }

    /** Chua vuot du nguong: troi nhe nhang ve vi tri cu, khong gui gi. */
    private fun animateSpringBack() {
        imageReviewPhoto.animate()
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
        textReviewHint.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(220L)
            .start()
    }

    /**
     * Doc + xoay lai theo EXIF (BitmapFactory khong tu doc huong anh) + giam kich
     * thuoc ve toi da [PHOTO_SEND_MAX_LONG_EDGE] o canh dai + nen JPEG chat luong
     * [PHOTO_SEND_JPEG_QUALITY] - dung cung do phan giai/muc nen voi luong video de
     * gui/nhan nhanh tuong duong. Neu decode/nen that bai vi ly do gi do, tra ve
     * bytes GOC (khong lam mat anh) - chi la se gui cham hon binh thuong.
     */
    private fun compressPhotoForSending(original: ByteArray): ByteArray {
        try {
            val rotationDegrees = try {
                val exif = ExifInterface(java.io.ByteArrayInputStream(original))
                when (exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (e: Exception) { 0f }

            val decoded = BitmapFactory.decodeByteArray(original, 0, original.size) ?: return original
            val rotated = if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else decoded

            val longEdge = maxOf(rotated.width, rotated.height)
            val scale = if (longEdge > PHOTO_SEND_MAX_LONG_EDGE) PHOTO_SEND_MAX_LONG_EDGE.toFloat() / longEdge else 1f
            val resized = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    rotated, (rotated.width * scale).toInt().coerceAtLeast(1),
                    (rotated.height * scale).toInt().coerceAtLeast(1), true
                ).also { if (it !== rotated) rotated.recycle() }
            } else rotated

            val out = java.io.ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, PHOTO_SEND_JPEG_QUALITY, out)
            resized.recycle()
            return out.toByteArray()
        } catch (e: Exception) {
            return original // that bai thi gui ban goc, con hon khong gui duoc gi
        }
    }

    /**
     * Doc noi dung anh vua chup roi gui sang may tinh qua DataChannel (xem
     * CameraStreamService.sendFileToAllViewers() + PeerConnectionManager.
     * sendFile()). Chay tren luong nen - doc file + gui tung doan khong nen
     * lam tren luong UI. Chi duoc goi khi nguoi dung CHU DONG vuot 2 ngon len
     * tren de gui (khong con tu dong gui ngay sau khi chup nua).
     */
    private fun sendPhotoToComputer(uri: Uri?) {
        if (uri == null) return
        Thread {
            try {
                val originalBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@Thread
                val bytes = compressPhotoForSending(originalBytes)
                val name = "anh_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
                val sentCount = CameraStreamService.instance?.sendFileToAllViewers(
                    bytes, name, "image/jpeg"
                ) { _, success ->
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (success) "Đã gửi ảnh sang máy tính" else "Gửi ảnh thất bại (mất kết nối giữa chừng)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } ?: 0
                if (sentCount == 0) {
                    runOnUiThread {
                        Toast.makeText(
                            this, "Chưa có máy tính nào đang kết nối để gửi", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Lỗi khi đọc ảnh để gửi: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun openLastPhotoViewer() {
        val uri = lastPhotoUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Không mở được trình xem ảnh", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_ROOM_CODE = "room_code"
    }
}
