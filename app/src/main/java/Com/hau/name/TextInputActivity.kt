package Com.hau.name

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * Man hinh NHAP CHU sang may tinh: go tren dien thoai -> chu hien NGAY tai vi tri con tro
 * dang dat trong trang web tren may tinh (app Qrtuxa tu chen vao).
 *
 * Co che dong bo (de dam bao DU, khong sot chu):
 *  - [sentText] = noi dung may tinh dang co tu phien nhap nay. Moi lan o nhap doi, so sanh
 *    voi [sentText]: giu phan dau chung, XOA phan duoi cu (del ky tu) roi CHEN phan moi (ins).
 *    Cach nay dung cho moi kieu sua (go, xoa, dan, doi tu bo go tu dong...).
 *  - Chi cap nhat [sentText] khi gui THANH CONG. Chua ket noi/rot mang thi giu nguyen va TU
 *    GUI LAI (moi 1.5s) cho toi khi dong bo xong - khong mat chu.
 *  - Moi thao tac chay tuan tu tren 1 luong (executor) -> dung thu tu.
 *  - O nhap chi hien 1 dong (dong gan nhat), nhung van ban day du van duoc giu va gui di.
 *  - "Dan": lay noi dung bo nho tam, chen vao o nhap -> gui nguyen van ban (ke ca rat dai,
 *    nhieu dong) sang may tinh.
 */
class TextInputActivity : AppCompatActivity() {

    private lateinit var editInput: EditText
    private lateinit var textStatus: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())

    /** Chi doc/ghi tren [executor]. */
    private var sentText = ""
    @Volatile private var latestText = ""
    private var ignoreChanges = false
    private var retryScheduled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_input)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        editInput = findViewById(R.id.edit_input)
        textStatus = findViewById(R.id.text_input_status)

        editInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (ignoreChanges) return
                latestText = s?.toString() ?: ""
                submitSync(latestText)
            }
        })

        findViewById<Button>(R.id.btn_paste).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.btn_clear_box).setOnClickListener { clearBoxOnly() }
        findViewById<Button>(R.id.btn_text_input_back).setOnClickListener { finish() }

        editInput.requestFocus()
        setStatus("Sẵn sàng")
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (e: Exception) { null }
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Bộ nhớ tạm đang trống", Toast.LENGTH_SHORT).show()
            return
        }
        val editable = editInput.text
        val a = editInput.selectionStart.coerceAtLeast(0)
        val b = editInput.selectionEnd.coerceAtLeast(0)
        // Chen vao o nhap -> TextWatcher tu gui phan chen (nguyen van, giu xuong dong).
        editable.replace(minOf(a, b), maxOf(a, b), text)
        editInput.setSelection(editInput.text.length.coerceAtMost(minOf(a, b) + text.length))
        Toast.makeText(this, "Đã dán ${text.length} ký tự", Toast.LENGTH_SHORT).show()
    }

    /** Lam trong o nhap tren dien thoai, KHONG gui thao tac xoa nao sang may tinh. */
    private fun clearBoxOnly() {
        ignoreChanges = true
        editInput.setText("")
        ignoreChanges = false
        latestText = ""
        executor.execute { sentText = "" }
        setStatus("Đã làm trống ô (chữ trên web giữ nguyên)")
    }

    private fun submitSync(target: String) {
        executor.execute { syncOnce(target) }
    }

    private fun syncOnce(target: String) {
        if (target == sentText) {
            setStatus("✓ Đã gửi")
            return
        }
        val old = sentText
        var p = 0
        val max = minOf(old.length, target.length)
        while (p < max && old[p] == target[p]) p++
        // Khong cat giua cap ky tu thay the (emoji)
        if (p > 0 && p < old.length && Character.isHighSurrogate(old[p - 1])) p--
        if (p > 0 && p < target.length && Character.isHighSurrogate(target[p - 1])) p--
        val del = old.codePointCount(p, old.length)
        val ins = target.substring(p)

        val sent = CameraStreamService.instance?.sendTextEditToAllViewers(del, ins) ?: 0
        if (sent > 0) {
            sentText = target
            setStatus("✓ Đã gửi (${target.length} ký tự)")
        } else {
            setStatus("⏳ Chưa kết nối máy tính - sẽ tự gửi lại khi kết nối")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        uiHandler.post {
            if (retryScheduled) return@post
            retryScheduled = true
            uiHandler.postDelayed({
                retryScheduled = false
                if (!isFinishing) submitSync(latestText)
            }, 1500L)
        }
    }

    private fun setStatus(msg: String) {
        runOnUiThread { if (!isDestroyed) textStatus.text = msg }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        executor.shutdown()
    }

    companion object {
        const val EXTRA_ROOM_CODE = "room_code"
    }
}
