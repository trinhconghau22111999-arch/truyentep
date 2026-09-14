package Com.hau.name

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * Man hinh Gui tep (mo tu nut "📤 Gui tep" tren CameraActivity).
 *
 * STUB - se hoan thien o Giai doan 4:
 * - Bam de chon nhieu tep/anh/video co san tren may (ACTION_OPEN_DOCUMENT,
 *   ALLOW_MULTIPLE)
 * - Nut "Gui" de truyen qua may tinh
 * - Chi cho phep truyen khi ca 2 dang ket noi thanh cong (loi ket noi -> huy)
 * - Gui tung phan qua WebRTC DataChannel, may tinh ghep lai thanh file (xem
 *   Giai doan 3)
 */
class SendFileActivity : AppCompatActivity() {

    private var roomCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_file)
        roomCode = intent.getStringExtra(EXTRA_ROOM_CODE)

        findViewById<Button>(R.id.btn_send_file_back).setOnClickListener { finish() }
    }

    companion object {
        const val EXTRA_ROOM_CODE = "room_code"
    }
}
