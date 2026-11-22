package com.rehan.otgprint

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * يحوّل صفحة (Bitmap) إلى بيانات PCL 5 raster أبيض/أسود بدقة 300 dpi.
 * مناسب لطابعات HP LaserJet (مثل P3005) وأغلب الطابعات اللي بتدعم PCL.
 */
object PclEncoder {

    const val DPI = 300

    // لوحة A4 كاملة بدقة 300 dpi
    const val CANVAS_W = 2480
    const val CANVAS_H = 3508

    // المنطقة القابلة للطباعة فعلياً (Logical page) على A4 بدقة 300 dpi
    private const val LEFT_OFFSET = 50
    private const val LOGICAL_W = 2336            // لازم يكون من مضاعفات 8
    private const val LOGICAL_H = 3380
    private const val ROW_BYTES = LOGICAL_W / 8   // 292

    private const val ESC = 0x1B.toChar()

    private const val THRESHOLD = 160

    // مصفوفة Bayer 4x4 للـ dithering (وضع الصور)
    private val BAYER = intArrayOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5
    )

    fun jobStart(): ByteArray {
        val s = StringBuilder()
        s.append(ESC).append("%-12345X")          // UEL
        s.append("@PJL ENTER LANGUAGE=PCL\r\n")
        s.append(ESC).append("E")                   // Reset
        return s.toString().toByteArray(Charsets.ISO_8859_1)
    }

    fun jobEnd(): ByteArray {
        val s = StringBuilder()
        s.append(ESC).append("E")
        s.append(ESC).append("%-12345X")
        return s.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /** يحوّل لوحة CANVAS_W x CANVAS_H إلى صفحة PCL كاملة. */
    fun encodePage(bmp: Bitmap, photoMode: Boolean): ByteArray {
        require(bmp.width == CANVAS_W && bmp.height == CANVAS_H) { "حجم الصفحة غير صحيح" }

        val out = ByteArrayOutputStream(256 * 1024)

        val header = StringBuilder()
        header.append(ESC).append("&l26A")      // ورق A4
        header.append(ESC).append("&l0O")       // Portrait
        header.append(ESC).append("&l0E")       // top margin = 0
        header.append(ESC).append("&l0L")       // perforation skip off
        header.append(ESC).append("*t${DPI}R")  // الدقة
        header.append(ESC).append("*r0F")       // اتجاه الرسم
        header.append(ESC).append("*p0x0Y")     // المؤشر عند 0,0
        header.append(ESC).append("*r1A")       // بدء الرسم
        header.append(ESC).append("*b2M")       // ضغط PackBits
        out.write(header.toString().toByteArray(Charsets.ISO_8859_1))

        val pixels = IntArray(CANVAS_W)
        val row = ByteArray(ROW_BYTES)
        val packed = ByteArrayOutputStream(ROW_BYTES * 2)

        for (y in 0 until LOGICAL_H) {
            bmp.getPixels(pixels, 0, CANVAS_W, 0, y, CANVAS_W, 1)
            java.util.Arrays.fill(row, 0)

            val by = (y and 3) shl 2
            for (x in 0 until LOGICAL_W) {
                val p = pixels[LEFT_OFFSET + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val gray = (r * 77 + g * 151 + b * 28) shr 8

                val black = if (photoMode) {
                    val thr = BAYER[by or (x and 3)] * 16 + 8
                    gray < thr
                } else {
                    gray < THRESHOLD
                }
                if (black) {
                    row[x shr 3] = (row[x shr 3].toInt() or (0x80 shr (x and 7))).toByte()
                }
            }

            packed.reset()
            packBits(row, ROW_BYTES, packed)
            out.write("$ESC*b${packed.size()}W".toByteArray(Charsets.ISO_8859_1))
            packed.writeTo(out)
        }

        out.write("$ESC*rB".toByteArray(Charsets.ISO_8859_1))   // نهاية الرسم
        out.write(0x0C)                                           // Form feed = إخراج الصفحة
        return out.toByteArray()
    }

    /** ضغط PackBits (TIFF) - Compression mode 2 في PCL. */
    private fun packBits(src: ByteArray, len: Int, out: ByteArrayOutputStream) {
        var i = 0
        while (i < len) {
            var run = 1
            while (i + run < len && run < 128 && src[i + run] == src[i]) run++
            if (run >= 2) {
                out.write(257 - run)
                out.write(src[i].toInt() and 0xFF)
                i += run
            } else {
                var j = i + 1
                while (j < len && j - i < 128 && !(j + 1 < len && src[j] == src[j + 1])) j++
                val n = j - i
                out.write(n - 1)
                out.write(src, i, n)
                i = j
            }
        }
    }
}
