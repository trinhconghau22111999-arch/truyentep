package Com.hau.name

import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

/**
 * Điểm truy cập DUY NHẤT tới Firebase Realtime Database.
 *
 * Chỉ định URL tường minh (trùng với desktop Qrtuxa: desktop/index.html -> FIREBASE_CONFIG.databaseURL)
 * thay vì phụ thuộc trường firebase_url trong google-services.json. DB nằm ở vùng
 * asia-southeast1; nếu google-services.json thiếu/sai firebase_url thì
 * FirebaseDatabase.getInstance() sẽ nối nhầm DB khác và 2 bên không bao giờ thấy nhau.
 */
object FirebaseDb {
    const val DB_URL = "https://qrremod-default-rtdb.asia-southeast1.firebasedatabase.app"

    val root: DatabaseReference
        get() = FirebaseDatabase.getInstance(DB_URL).reference
}
