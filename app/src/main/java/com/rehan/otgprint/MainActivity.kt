package com.rehan.otgprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) printUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun title(t: String) = TextView(this).apply {
            text = t; textSize = 22f; setTypeface(typeface, Typeface.BOLD)
        }

        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

        root.addView(title("طباعة عن طريق OTG"))

        status = TextView(this).apply {
            text = "جاهز. وصّل الطابعة بكابل OTG ثم اضغط \"اكتشاف الطابعة\"."
            textSize = 16f
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(status)

        root.addView(button("اكتشاف الطابعة وطلب الإذن") { detect() })
        root.addView(button("طباعة صفحة اختبار") { printTest() })
        root.addView(button("اختيار ملف PDF وطباعته") { picker.launch(arrayOf("application/pdf")) })

        val photo = CheckBox(this).apply {
            text = "وضع الصور (Dithering) - الأفضل للصور، والعادي أحدّ للنصوص"
            isChecked = PrintEngine.isPhotoMode(this@MainActivity)
            setOnCheckedChangeListener { _, v -> PrintEngine.setPhotoMode(this@MainActivity, v) }
        }
        root.addView(photo)

        root.addView(TextView(this).apply {
            text = "\nلطباعة ملفات Word أو أي تطبيق تاني: افتح الملف، اختار Print من القائمة، " +
                "ثم اختار الطابعة \"USB LaserJet (PCL)\"."
            textSize = 14f
            gravity = Gravity.START
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setStatus(t: String) = runOnUiThread { status.text = t }

    private fun detect() {
        Thread {
            try {
                val p = UsbPrinter(this)
                val d = p.findDevice()
                if (d == null) {
                    setStatus("مفيش طابعة متوصلة. اتأكد من الكابل ومن تشغيل الطابعة.")
                    return@Thread
                }
                setStatus("لقيت جهاز (VID=%04X, PID=%04X). بطلب الإذن...".format(d.vendorId, d.productId))
                val ok = p.requestPermissionBlocking(d)
                setStatus(if (ok) "الطابعة جاهزة ✔ (VID=%04X, PID=%04X)".format(d.vendorId, d.productId)
                          else "الإذن اترفض. جرّب تاني ووافق.")
            } catch (e: Exception) {
                setStatus("خطأ: ${e.message}")
            }
        }.start()
    }

    private fun printTest() {
        Thread {
            try {
                setStatus("جاري تجهيز صفحة الاختبار...")
                PrintEngine.printBitmap(this, buildTestPage())
                setStatus("اتبعتت صفحة الاختبار للطابعة ✔")
            } catch (e: Exception) {
                setStatus("خطأ: ${e.message}")
            }
        }.start()
    }

    private fun printUri(uri: Uri) {
        Thread {
            try {
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("تعذّر فتح الملف")
                PrintEngine.printPdf(this, pfd, 1, onProgress = { page, total ->
                    setStatus("جاري طباعة صفحة $page من $total ...")
                })
                setStatus("تمت الطباعة ✔")
            } catch (e: Exception) {
                setStatus("خطأ: ${e.message}")
            }
        }.start()
    }

    private fun buildTestPage(): Bitmap {
        val bmp = Bitmap.createBitmap(PclEncoder.CANVAS_W, PclEncoder.CANVAS_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val stroke = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 6f }
        c.drawRect(120f, 120f, (PclEncoder.CANVAS_W - 120).toFloat(), (PclEncoder.CANVAS_H - 120).toFloat(), stroke)

        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 140f; textAlign = Paint.Align.CENTER }
        val mid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 80f; textAlign = Paint.Align.CENTER }

        val cx = PclEncoder.CANVAS_W / 2f
        c.drawText("OTG Print Test", cx, 600f, big)
        c.drawText("اختبار الطباعة عبر OTG", cx, 800f, big)
        c.drawText("Galaxy A35  →  LaserJet (PCL)", cx, 1000f, mid)
        c.drawText(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()), cx, 1150f, mid)

        // خطوط اختبار للدقة
        for (i in 0 until 12) {
            val y = 1400f + i * 60f
            val p = Paint().apply { color = Color.BLACK; strokeWidth = (i + 1).toFloat() }
            c.drawLine(300f, y, (PclEncoder.CANVAS_W - 300).toFloat(), y, p)
        }

        // تدرج رمادي لاختبار وضع الصور
        val steps = 16
        val w = (PclEncoder.CANVAS_W - 600) / steps
        for (i in 0 until steps) {
            val g = 255 - i * 255 / (steps - 1)
            val p = Paint().apply { color = Color.rgb(g, g, g); style = Paint.Style.FILL }
            c.drawRect(300f + i * w, 2400f, 300f + (i + 1) * w, 2800f, p)
        }
        return bmp
    }
}
