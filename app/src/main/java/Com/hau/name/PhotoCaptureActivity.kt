package Com.hau.name

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
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
 * - Khi chup: luu anh xuong may (MediaStore, thu muc Pictures/QrTruyenTep)
 *   DONG THOI gui qua may tinh (Giai doan 3 se noi that vao
 *   sendPhotoToComputer() - hien tai la cho o do).
 */
class PhotoCaptureActivity : AppCompatActivity() {

    private var roomCode: String? = null

    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: View
    private lateinit var btnFlash: TextView
    private lateinit var btnViewLastPhoto: ImageView
    private lateinit var btnExit: TextView

    private var imageCapture: ImageCapture? = null
    private var flashOn = false
    private var lastPhotoUri: Uri? = null

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

        btnExit.setOnClickListener { finish() }
        btnCapture.setOnClickListener { takePhoto() }
        btnFlash.setOnClickListener { toggleFlash() }
        btnViewLastPhoto.setOnClickListener { openLastPhotoViewer() }

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
                    Toast.makeText(this@PhotoCaptureActivity, "Đã lưu ảnh", Toast.LENGTH_SHORT).show()
                    // Giai doan 3: gui anh nay sang may tinh qua WebRTC DataChannel
                    sendPhotoToComputer(lastPhotoUri)
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(
                        this@PhotoCaptureActivity, "Chụp ảnh thất bại: ${exc.message}", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    /**
     * Doc noi dung anh vua chup roi gui sang may tinh qua DataChannel (xem
     * CameraStreamService.sendFileToAllViewers() + PeerConnectionManager.
     * sendFile()). Chay tren luong nen - doc file + gui tung doan khong nen
     * lam tren luong UI.
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
