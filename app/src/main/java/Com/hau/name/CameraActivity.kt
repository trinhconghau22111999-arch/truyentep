package Com.hau.name

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Máy B (điện thoại cũ đóng vai trò camera giám sát, đặt cố định trong nhà).
 *
 * Luồng bắt buộc:
 * 1. Người dùng tự tick đồng ý -> nút "Bắt đầu làm camera" mới bật.
 * 2. Bấm nút sẽ xin quyền CAMERA (hộp thoại hệ thống, không tùy biến được).
 * 3. Sau khi cấp quyền, tạo mã ghép nối 6 số ngẫu nhiên, ghi lên Firebase,
 *    rồi khởi động CameraStreamService (foreground service) để bật camera sau
 *    và bắt đầu truyền hình ảnh thời gian thực qua WebRTC.
 * 4. Máy A (máy xem) phải nhập ĐÚNG mã 6 số này mới xem được — máy B không
 *    tự kết nối hay hiện hình cho bất kỳ ai không có mã.
 * 5. Máy B KHÔNG nhận bất kỳ lệnh điều khiển nào từ máy A (không có kênh
 *    input injection) — chỉ một chiều: quay và gửi hình đi.
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var checkboxConsent: CheckBox
    private lateinit var btnStart: Button
    private lateinit var layoutPairingCode: android.widget.LinearLayout
    private lateinit var textPairingCode: TextView
    private lateinit var btnEndSession: Button
    private lateinit var btnTakePhoto: Button
    private lateinit var btnSendFile: Button

    private var roomCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        checkboxConsent = findViewById(R.id.checkbox_consent)
        btnStart = findViewById(R.id.btn_generate_code)
        layoutPairingCode = findViewById(R.id.layout_pairing_code)
        textPairingCode = findViewById(R.id.text_pairing_code)
        btnEndSession = findViewById(R.id.btn_end_session)
        btnTakePhoto = findViewById(R.id.btn_take_photo)
        btnSendFile = findViewById(R.id.btn_send_file)

        checkboxConsent.setOnCheckedChangeListener { _, isChecked ->
            btnStart.isEnabled = isChecked
        }
        btnStart.isEnabled = false

        btnStart.setOnClickListener { requestCameraPermissionThenStart() }
        btnEndSession.setOnClickListener { endSession() }
        btnTakePhoto.setOnClickListener {
            startActivity(Intent(this, PhotoCaptureActivity::class.java).apply {
                putExtra(PhotoCaptureActivity.EXTRA_ROOM_CODE, roomCode)
            })
        }
        btnSendFile.setOnClickListener {
            startActivity(Intent(this, SendFileActivity::class.java).apply {
                putExtra(SendFileActivity.EXTRA_ROOM_CODE, roomCode)
            })
        }

        findViewById<Button>(R.id.btn_battery_fix).setOnClickListener {
            BatteryOptimizationHelper.requestIgnore(this)
        }

        setupInitialScreen()
    }

    /**
     * Chỉ lần MỞ APP ĐẦU TIÊN (chưa từng đồng ý) mới hiện màn hình tick đồng ý + giải thích.
     * Từ lần thứ 2 trở đi (KEY_CONSENT_GIVEN = true), ẩn hẳn phần giải thích/tick đồng ý và
     * TỰ ĐỘNG bật lại webcam ngay khi vào app — không cần bấm gì cả, giống hệt màn hình đang
     * hoạt động (mã ghép nối) mà không phải xem lại lời giải thích mỗi lần.
     */
    private fun setupInitialScreen() {
        val consentGiven = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_CONSENT_GIVEN, false)

        if (!consentGiven) {
            // Lần đầu tiên: giữ nguyên luồng cũ - phải tick đồng ý rồi mới bấm được nút bắt đầu.
            return
        }

        // Từ lần thứ 2 trở đi: ẩn vĩnh viễn phần tiêu đề/giải thích/tick đồng ý,
        // nút bắt đầu chỉ còn dùng để "bật lại" thủ công nếu người dùng lỡ bấm "Kết thúc phiên".
        findViewById<TextView>(R.id.text_title).visibility = android.view.View.GONE
        findViewById<TextView>(R.id.text_body).visibility = android.view.View.GONE
        checkboxConsent.visibility = android.view.View.GONE
        btnStart.text = getString(R.string.btn_generate_code)
        btnStart.isEnabled = true

        // Nếu webcam đang thực sự chạy (chưa bị "Kết thúc phiên" hay hệ thống dọn hẳn app),
        // hiện lại mã ghép nối thay vì bắt bấm lại từ đầu.
        restoreActiveSessionIfAny()

        // Trường hợp còn lại (chưa có phiên đang chạy) - đây chính là lúc app được mở lại sau
        // khi trước đó đã bị thoát hẳn (CameraStreamService.onTaskRemoved đã tự tắt webcam) -
        // tự động bật lại webcam ngay, không cần người dùng thao tác gì thêm.
        if (layoutPairingCode.visibility != android.view.View.VISIBLE) {
            requestCameraPermissionThenStart()
        }
    }

    /**
     * SỬA LỖI "bấm back là văng app": đây là Activity DUY NHẤT trong app (MainActivity tự
     * finish() ngay sau khi mở CameraActivity), nên back mặc định sẽ finish() nốt luôn Activity
     * cuối cùng này -> hệ thống dọn sạch task, cảm giác như app bị "văng"/đóng đột ngột — dù
     * CameraStreamService (camera + WebRTC) có thể vẫn đang chạy nền phía sau, việc mất hẳn màn
     * hình UI đột ngột vẫn gây cảm giác app crash. Đây vốn là app "chạy nền" (camera giám sát),
     * nên back phải đưa app xuống nền (giống bấm Home) chứ không đóng hẳn - camera/service vẫn
     * tiếp tục chạy đúng như thiết kế, người dùng có thể mở lại app bất cứ lúc nào từ Recents.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onResume() {
        super.onResume()
        findViewById<android.view.View>(R.id.banner_battery).visibility =
            if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun restoreActiveSessionIfAny() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val fixedCode = prefs.getString(KEY_FIXED_CODE, null) ?: return
        // Quan trọng: chỉ hiện lại bảng mã (như đang hoạt động) nếu phiên THỰC SỰ đang chạy
        // (cờ KEY_SESSION_ACTIVE) — có mã cố định không đồng nghĩa camera đang bật. Thiếu cờ
        // này sẽ khiến app hiện nhầm "đang chạy" ngay cả khi đã bấm "Kết thúc phiên" từ trước.
        if (!prefs.getBoolean(KEY_SESSION_ACTIVE, false)) return
        roomCode = fixedCode
        checkboxConsent.isChecked = true
        textPairingCode.text = fixedCode
        layoutPairingCode.visibility = android.view.View.VISIBLE
        btnSendFile.visibility = android.view.View.VISIBLE
        btnStart.visibility = android.view.View.GONE
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
            Toast.makeText(this, "Cần quyền Camera để dùng máy này làm camera giám sát", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Mã ghép nối giờ CỐ ĐỊNH theo từng máy — chỉ sinh ngẫu nhiên 1 LẦN DUY NHẤT khi máy này
     * lần đầu được dùng làm camera, sau đó lưu lại vĩnh viễn và tái sử dụng mỗi lần mở app
     * (kể cả sau khi "Kết thúc phiên" hay khởi động lại máy) — không đổi mã nữa.
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

        com.google.firebase.database.FirebaseDatabase.getInstance().reference
            .child("rooms").child(code).setValue(
                mapOf("status" to "waiting", "consentGivenAt" to System.currentTimeMillis())
            ).addOnFailureListener { e ->
                Toast.makeText(this, "Không thể ghi trạng thái phòng lên máy chủ: ${e.message}",
                    Toast.LENGTH_LONG).show()
            }

        textPairingCode.text = code
        layoutPairingCode.visibility = android.view.View.VISIBLE
        btnSendFile.visibility = android.view.View.VISIBLE
        btnStart.visibility = android.view.View.GONE
        // Đánh dấu đã từng đồng ý - từ lần mở app sau sẽ không hiện lại màn giải thích nữa,
        // chỉ tự động bật thẳng webcam.
        prefs.edit()
            .putBoolean(KEY_SESSION_ACTIVE, true)
            .putBoolean(KEY_CONSENT_GIVEN, true)
            .apply()

        BatteryOptimizationHelper.requestIgnore(this)
    }

    /** Kết thúc phiên: dừng camera qua CÙNG 1 đường với nút "Kết thúc" trên notification
     *  (ACTION_STOP_SHARING) — đảm bảo Firebase luôn được dọn sạch (status + viewers) như
     *  nhau dù dừng từ đâu, và giữ nguyên mã cố định để lần sau dùng lại được. */
    private fun endSession() {
        startService(Intent(this, CameraStreamService::class.java).apply {
            action = CameraStreamService.ACTION_STOP_SHARING
        })
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_SESSION_ACTIVE, false).apply()
        layoutPairingCode.visibility = android.view.View.GONE
        btnSendFile.visibility = android.view.View.GONE
        checkboxConsent.isChecked = false
        // Đã từng đồng ý rồi thì nút này chỉ còn là nút "bật lại" đơn giản, không cần tick lại.
        btnStart.isEnabled = true
        btnStart.visibility = android.view.View.VISIBLE
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 2001
        const val PREFS_NAME = "home_camera"
        /** Mã ghép nối cố định của máy này — sinh 1 lần, dùng mãi mãi. */
        const val KEY_FIXED_CODE = "fixed_room_code"
        /** true khi phiên camera THỰC SỰ đang chạy (không chỉ là "đã từng có mã") — dùng để
         *  UI không hiện nhầm "đang hoạt động" sau khi đã bấm Kết thúc rồi mở lại app. */
        const val KEY_SESSION_ACTIVE = "session_active"
        /** true ngay sau lần đầu tiên người dùng tick đồng ý + bấm "Bắt đầu làm Webcam".
         *  Từ đó về sau, KHÔNG hiện lại màn giải thích/tick đồng ý nữa - mỗi lần mở app sẽ
         *  tự động bật thẳng webcam (xem [setupInitialScreen]). */
        const val KEY_CONSENT_GIVEN = "consent_given"
    }
}
