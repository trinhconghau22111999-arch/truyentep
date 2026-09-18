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
import android.view.View
import android.view.animation.OvershootInterpolator
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
import androidx.camera.core.MirrorMode
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

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var flashOn = false
    private var lastPhotoUri: Uri? = null
    /** Camera dang dung - mac dinh camera SAU, doi qua lai khi bam [btnSwitchCamera]. */
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var cameraProvider: ProcessCameraProvider? = null

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

        // Man hinh nay gio la man hinh chinh duy nhat cua app (thay cho CameraActivity
        // truoc day). KHONG con nut thoat rieng - bam Back (nut he thong) se thoat han
        // + ngat ket noi (xem onBackPressed / exitAppAndDisconnect).
        btnCapture.setOnClickListener { takePhoto() }
        btnFlash.setOnClickListener { toggleFlash() }
        btnSwitchCamera.setOnClickListener { toggleCamera() }
        btnViewLastPhoto.setOnClickListener { openLastPhotoViewer() }
        btnSendFile.setOnClickListener {
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

    /** Cong them padding/margin = chieu cao status bar (tren) va navigation bar (duoi) cho
     *  hang nut phia tren (chip ma, nut Gui tep) va hang nut phia duoi (chup/flash/xem anh) -
     *  de cac nut nay khong bi status bar (dong ho, pin...) hay navigation bar de len, trong
     *  khi khung camera (camera_preview) van tran het toan bo man hinh phia sau. */
    private fun setupEdgeToEdgeInsets() {
        val baseTopMargin = (16 * resources.displayMetrics.density).toInt()
        val baseBottomMargin = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            layoutTopControls.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = baseTopMargin + bars.top
            }
            layoutConnectionStatus.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = baseTopMargin + bars.top
            }
            bottomControls.updateLayoutParams<ConstraintLayout.LayoutParams> {
                bottomMargin = baseBottomMargin + bars.bottom
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

    override fun onStop() {
        super.onStop()
        statusListenerHandler.removeCallbacks(registerStatusListenerRunnable)
        CameraStreamService.instance?.removeConnectionStatusListener(connectionStatusListener)
    }

    private fun startCamera() {
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
                // Camera TRUOC: preview tren man hinh da tu dong lat guong (hanh vi
                // mac dinh cua CameraX Preview) nhung anh LUU RA lai KHONG lat theo
                // mac dinh -> nguoi dung thay preview 1 kieu, anh luu ra lai nguoc
                // (chu/vat bi lat trai-phai so voi nhung gi ho thay luc chup). Bat
                // MIRROR_MODE_ON_FRONT_ONLY de anh LUU RA cung duoc lat giong nhu
                // preview khi dung camera truoc (camera sau khong bi anh huong).
                .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
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
            } catch (e: Exception) {
                Toast.makeText(this, "Không mở được camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Doi qua lai camera truoc/sau - bam nut o vi tri cu cua nut flash (xem
     *  activity_photo_capture.xml). Kiem tra may co camera do khong truoc khi doi
     *  (mot so may chi co 1 camera) de tranh crash; neu khong co thi bao va giu nguyen. */
    private fun toggleCamera() {
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
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    lastPhotoUri = output.savedUri
                    btnViewLastPhoto.visibility = View.VISIBLE
                    lastPhotoUri?.let { btnViewLastPhoto.setImageURI(it) }
                    // Chi luu tren may thoi - KHONG tu dong gui. Hien anh vua chup full man
                    // hinh de xem lai, chi gui khi nguoi dung chu dong vuot 2 ngon len tren.
                    lastPhotoUri?.let { openPhotoReview(it) }
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(
                        this@PhotoCaptureActivity, "Chụp ảnh thất bại: ${exc.message}", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    /** Hien anh vua chup full man hinh, san sang cho vuot 2 ngon de gui. */
    private fun openPhotoReview(uri: Uri) {
        imageReviewPhoto.setImageURI(uri)
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
