package Com.hau.name

import android.os.Bundle
import android.provider.OpenableColumns
import android.net.Uri
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Man hinh Gui tep (mo tu nut "📤 Gui tep" tren CameraActivity).
 *
 * - Bam "Chon tep/anh/video" de chon NHIEU tep cung luc (ACTION_OPEN_
 *   DOCUMENT, cho phep chon moi loai tep).
 * - Bam "Gui" de truyen LAN LUOT tung tep sang may tinh (khong gui song
 *   song nhieu tep cung luc - tranh lam roi giao thuc nhan tung doan ben
 *   may tinh, vi may tinh hien chi theo doi DUNG 1 phien nhan tai 1 thoi
 *   diem cho moi dien thoai).
 * - Chi cho phep gui khi dang co it nhat 1 may tinh ket noi thanh cong
 *   (CameraStreamService.sendFileToAllViewers() tu kiem tra dieu nay) -
 *   khong co may nao dang ket noi thi HUY LUON ca lo gui, khong gui tiep
 *   cac tep con lai.
 */
class SendFileActivity : AppCompatActivity() {

    private var roomCode: String? = null
    private val selectedUris = mutableListOf<Uri>()

    private lateinit var btnPickFiles: Button
    private lateinit var btnSendNow: Button
    private lateinit var btnBack: Button
    private lateinit var textSelectedFiles: TextView
    private lateinit var progressSending: ProgressBar
    private lateinit var textSendingStatus: TextView

    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedUris.clear()
            selectedUris.addAll(uris)
            uris.forEach {
                try {
                    contentResolver.takePersistableUriPermission(
                        it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) { /* mot so provider khong ho tro, bo qua */ }
            }
            renderSelectedFiles()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_file)
        roomCode = intent.getStringExtra(EXTRA_ROOM_CODE)

        btnPickFiles = findViewById(R.id.btn_pick_files)
        btnSendNow = findViewById(R.id.btn_send_now)
        btnBack = findViewById(R.id.btn_send_file_back)
        textSelectedFiles = findViewById(R.id.text_selected_files)
        progressSending = findViewById(R.id.progress_sending)
        textSendingStatus = findViewById(R.id.text_sending_status)

        btnBack.setOnClickListener { finish() }
        btnPickFiles.setOnClickListener { pickFilesLauncher.launch(arrayOf("*/*")) }
        btnSendNow.setOnClickListener { startSendingAll() }
    }

    private fun renderSelectedFiles() {
        if (selectedUris.isEmpty()) {
            textSelectedFiles.text = "Chưa chọn tệp nào"
            btnSendNow.isEnabled = false
            return
        }
        val names = selectedUris.map { queryDisplayName(it) ?: "(tệp)" }
        textSelectedFiles.text = names.joinToString("\n") { "• $it" }
        btnSendNow.isEnabled = true
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (e: Exception) { null }
    }

    private fun startSendingAll() {
        if (selectedUris.isEmpty()) return
        btnSendNow.isEnabled = false
        btnPickFiles.isEnabled = false
        progressSending.visibility = android.view.View.VISIBLE
        textSendingStatus.visibility = android.view.View.VISIBLE
        sendFileAt(0)
    }

    private fun sendFileAt(index: Int) {
        if (index >= selectedUris.size) {
            textSendingStatus.text = "Đã gửi xong ${selectedUris.size} tệp"
            progressSending.visibility = android.view.View.GONE
            btnSendNow.isEnabled = true
            btnPickFiles.isEnabled = true
            return
        }
        val uri = selectedUris[index]
        val name = queryDisplayName(uri) ?: "tep_$index"
        textSendingStatus.text = "Đang gửi (${index + 1}/${selectedUris.size}): $name"

        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) {
                    runOnUiThread {
                        Toast.makeText(this, "Không đọc được: $name", Toast.LENGTH_SHORT).show()
                        sendFileAt(index + 1)
                    }
                    return@Thread
                }
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

                var expected = 0
                var finished = 0
                var anySuccess = false
                val lock = Any()

                expected = CameraStreamService.instance?.sendFileToAllViewers(bytes, name, mimeType) { _, success ->
                    synchronized(lock) {
                        finished++
                        if (success) anySuccess = true
                        if (finished >= expected) {
                            runOnUiThread {
                                Toast.makeText(
                                    this,
                                    (if (anySuccess) "Đã gửi: " else "Gửi thất bại: ") + name,
                                    Toast.LENGTH_SHORT
                                ).show()
                                if (anySuccess) {
                                    runOnUiThread { progressSending.progress = ((index + 1) * 100) / selectedUris.size }
                                    sendFileAt(index + 1)
                                } else {
                                    // Loi ket noi giua chung - HUY LUON ca lo gui, khong gui tiep cac tep con lai
                                    textSendingStatus.text = "Đã hủy gửi (mất kết nối giữa chừng)"
                                    progressSending.visibility = android.view.View.GONE
                                    btnSendNow.isEnabled = true
                                    btnPickFiles.isEnabled = true
                                }
                            }
                        }
                    }
                } ?: 0

                if (expected == 0) {
                    runOnUiThread {
                        Toast.makeText(this, "Chưa có máy tính nào đang kết nối - đã hủy gửi", Toast.LENGTH_LONG).show()
                        textSendingStatus.text = "Đã hủy: chưa có máy tính kết nối"
                        progressSending.visibility = android.view.View.GONE
                        btnSendNow.isEnabled = true
                        btnPickFiles.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Lỗi khi gửi $name: ${e.message}", Toast.LENGTH_SHORT).show()
                    sendFileAt(index + 1)
                }
            }
        }.start()
    }

    companion object {
        const val EXTRA_ROOM_CODE = "room_code"
    }
}
