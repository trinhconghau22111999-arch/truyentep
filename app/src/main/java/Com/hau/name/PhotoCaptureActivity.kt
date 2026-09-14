package Com.hau.name

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * Man hinh Chup anh (mo tu nut "📷 Chup anh" tren CameraActivity).
 *
 * STUB - se hoan thien o Giai doan 2:
 * - Khung camera lon (preview truc tiep)
 * - Nut chup o giua duoi cung (vien tron xam day bong)
 * - Nut xem anh vua chup (ben trai nut chup - CHI hien sau khi chup, mac
 *   dinh an)
 * - Nut bat/tat flash (ben phai nut chup)
 * - Nut thoat che do chup (goc tren-trai)
 * - Khi chup: luu anh xuong may (MediaStore) DONG THOI gui qua may tinh qua
 *   WebRTC DataChannel (xem Giai doan 3)
 */
class PhotoCaptureActivity : AppCompatActivity() {

    private var roomCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_capture)
        roomCode = intent.getStringExtra(EXTRA_ROOM_CODE)

        findViewById<Button>(R.id.btn_photo_capture_exit).setOnClickListener { finish() }
    }

    companion object {
        const val EXTRA_ROOM_CODE = "room_code"
    }
}
