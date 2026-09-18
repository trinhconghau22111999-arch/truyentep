package Com.hau.name

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Buoc trung gian duy nhat cua app (khong con man hinh tick dong y / canh bao
 * pin nua - da bo theo yeu cau: app nay chi gui tep/anh THEO YEU CAU, khong
 * phai webcam quay lien tuc nen khong can loai tru toi uu pin, va cung khong
 * can mot man hinh giai thich rieng truoc khi vao camera).
 *
 * Luong:
 * 1. Xin quyen CAMERA (va THONG BAO tren Android 13+) bang hop thoai he thong.
 * 2. Sau khi co quyen: doc/tao ma ghep noi 6 so CO DINH cho may nay, ghi trang
 *    thai phong len Firebase, roi bat CameraStreamService (foreground service)
 *    y het truoc day - KHONG doi logic truyen tep.
 * 3. Chuyen thang sang PhotoCaptureActivity (man hinh chup anh nam FULL man
 *    hinh) va dong Activity nay lai - man hinh chup anh gio la man hinh chinh
 *    duy nhat nguoi dung nhin thay, voi ma ghep noi + nut "Gui tep" da co san
 *    duoc nhung ngay ben trong no.
 */
class CameraActivity : AppCompatActivity() {

    private var roomCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        requestCameraPermissionThenStart()
    }

    private fun requestCameraPermissionThenStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        } else {
            generatePairingCodeAndStartService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) return
        val cameraIndex = permissions.indexOf(Manifest.permission.CAMERA)
        val cameraGranted = cameraIndex == -1 || grantResults.getOrNull(cameraIndex) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            generatePairingCodeAndStartService()
        } else {
            Toast.makeText(this, "Cần quyền Camera để dùng máy này gửi ảnh/tệp", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /**
     * Ma ghep noi CO DINH theo tung may - chi sinh ngau nhien 1 LAN DUY NHAT khi
     * may nay lan dau duoc dung, sau do luu lai vinh vien va tai su dung moi lan
     * mo app (khong doi ma nua). Giu nguyen 100% logic ghi Firebase + khoi dong
     * CameraStreamService nhu truoc - CHI bo phan hien thi UI rieng cua man hinh
     * nay (khong con hien "dang tao ma", nut, banner...).
     */
    private fun generatePairingCodeAndStartService() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val code = prefs.getString(KEY_FIXED_CODE, null) ?: run {
            val newCode = (100000..999999).random().toString()
            prefs.edit().putString(KEY_FIXED_CODE, newCode).apply()
            newCode
        }
        roomCode = code

        val serviceIntent = Intent(this, CameraStreamService::class.java).apply {
            putExtra(CameraStreamService.EXTRA_ROOM_CODE, code)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        // SUA LOI NGHIEM TRONG: truoc day dung .setValue() ghi DE TOAN BO node
        // rooms/{code} - xoa sach ca nhanh "viewers" ben trong (du lieu may
        // tinh dang cho ket noi lai: present/hostGeneration...) MOI LAN mo
        // app. Ket qua: dung luc nguoi dung mo lai app de "ket noi lai ngay"
        // thi chinh hanh dong do lai xoa sach du lieu ma may tinh vua dang ky
        // lai de cho - khien khong bao gio ket noi lai duoc, phai nhap lai
        // ma moi duoc (dang ky mot viewerId MOI, khac voi cai vua bi xoa).
        // Dung updateChildren() de CHI cap nhat 2 truong nay, KHONG dung gi
        // toi "viewers" - de may tinh dang cho co co hoi duoc thay va ket noi.
        com.google.firebase.database.FirebaseDatabase.getInstance().reference
            .child("rooms").child(code).updateChildren(
                mapOf("status" to "waiting", "consentGivenAt" to System.currentTimeMillis())
            ).addOnFailureListener { e ->
                Toast.makeText(this, "Không thể ghi trạng thái phòng lên máy chủ: ${e.message}",
                    Toast.LENGTH_LONG).show()
            }

        prefs.edit()
            .putBoolean(KEY_SESSION_ACTIVE, true)
            .putBoolean(KEY_CONSENT_GIVEN, true)
            .apply()

        startActivity(Intent(this, PhotoCaptureActivity::class.java).apply {
            putExtra(PhotoCaptureActivity.EXTRA_ROOM_CODE, roomCode)
        })
        finish()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 2001
        const val PREFS_NAME = "home_camera"
        /** Mã ghép nối cố định của máy này — sinh 1 lần, dùng mãi mãi. */
        const val KEY_FIXED_CODE = "fixed_room_code"
        /** true khi phiên THỰC SỰ đang chạy — dùng bởi BootReceiver để tự khởi
         *  động lại CameraStreamService sau khi reboot/cập nhật app. */
        const val KEY_SESSION_ACTIVE = "session_active"
        /** Giữ lại cho tương thích ngược (BootReceiver/SharedPreferences cũ có thể
         *  vẫn đọc key này) — không còn dùng để hiện/ẩn màn hình giải thích nữa. */
        const val KEY_CONSENT_GIVEN = "consent_given"
    }
}
