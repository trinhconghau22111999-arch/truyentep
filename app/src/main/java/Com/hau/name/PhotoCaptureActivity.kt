package Com.hau.name

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Man hinh Chup anh (mo tu nut "📷 Chup anh" tren CameraActivity).
 *
 * - Khung camera lon (CameraX preview, camera SAU mac dinh)
 * - Nut chup o giua-duoi (vien tron xam day bong)
 * - Nut xem anh vua chup (ben trai nut chup) - CHI hien sau khi chup it
 *   nhat 1 tam trong phien nay, mac dinh an
 * - Nut bat/tat flash (ben phai nut chup)
 * - Nut thoat (goc tren-trai)
 * - Khi chup: CHI luu anh xuong may (MediaStore, thu muc Pictures/QrTruyenTep) -
 *   KHONG tu dong gui qua may tinh nua. Anh vua chup hien ngay full man hinh
 *   (layout_photo_review) de xem lai truoc:
 *     + Bam X goc tren-phai -> dong, KHONG gui, quay lai khung ngam.
 *     + Vuot 2 ngon tay tu duoi len tren TREN CHINH TAM ANH -> anh "bay" theo
 *       ngon tay len tren (hieu ung keo theo thoi gian thuc), tha tay khi da
 *       vuot qua nguong -> anh bay tiep len va bien mat, ĐONG THOI gui that
 *       sang may tinh (sendPhotoToComputer) - giong nhu dang "day" anh qua
 *       may tinh. Neu tha tay ma chua du nguong, anh tu troi lai vi tri cu.
 */
class PhotoCaptureActivity : AppCompatActivity() {

    private var roomCode: String? = null

    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: View
    private lateinit var btnFlash: TextView
    private lateinit var btnViewLastPhoto: ImageView
    private lateinit var btnExit: TextView

    private lateinit var layoutPhotoReview: FrameLayout
    private lateinit var imageReviewPhoto: ImageView
    private lateinit var textReviewHint: TextView
    private lateinit var btnCloseReview: TextView

    private var imageCapture: ImageCapture? = null
    private var flashOn = false
    private var lastPhotoUri: Uri? = null

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
        setContentView(R.layout.activity_photo_capture)
        roomCode = intent.getStringExtra(EXTRA_ROOM_CODE)

        previewView = findViewById(R.id.camera_preview)
        btnCapture = findViewById(R.id.btn_capture)
        btnFlash = findViewById(R.id.btn_toggle_flash)
        btnViewLastPhoto = findViewById(R.id.btn_view_last_photo)
        btnExit = findViewById(R.id.btn_exit_capture)

        layoutPhotoReview = findViewById(R.id.layout_photo_review)
        imageReviewPhoto = findViewById(R.id.image_review_photo)
        textReviewHint = findViewById(R.id.text_review_hint)
        btnCloseReview = findViewById(R.id.btn_close_review)

        btnExit.setOnClickListener { finish() }
        btnCapture.setOnClickListener { takePhoto() }
        btnFlash.setOnClickListener { toggleFlash() }
        btnViewLastPhoto.setOnClickListener { openLastPhotoViewer() }
        btnCloseReview.setOnClickListener { closePhotoReview() }
        setupSwipeUpToSend()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setFlashMode(if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Không mở được camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleFlash() {
        flashOn = !flashOn
        imageCapture?.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        // Doi mau chu de bao hieu ro trang thai bat/tat (vang = dang bat)
        btnFlash.setTextColor(if (flashOn) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt())
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

    /** Bam X: dong lai, KHONG gui gi ca, quay lai khung ngam camera. */
    private fun closePhotoReview() {
        layoutPhotoReview.visibility = View.GONE
        imageReviewPhoto.translationY = 0f
        imageReviewPhoto.alpha = 1f
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
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@Thread
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
