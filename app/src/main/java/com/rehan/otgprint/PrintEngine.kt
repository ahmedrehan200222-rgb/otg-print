package com.rehan.otgprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.IOException

object PrintEngine {

    private const val PREFS = "settings"
    private const val KEY_PHOTO = "photo_mode"

    fun isPhotoMode(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PHOTO, false)

    fun setPhotoMode(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PHOTO, v).apply()
    }

    /** يفتح الطابعة (ويطلب الإذن لو لازم) ويرجّع Session جاهزة. */
    private fun openPrinter(ctx: Context): UsbPrinter.Session {
        val printer = UsbPrinter(ctx)
        val dev = printer.findDevice() ?: throw IOException("مفيش طابعة USB متوصلة")
        DebugLog.d("لقيت الطابعة، بطلب الإذن...")
        if (!printer.requestPermissionBlocking(dev)) throw IOException("إذن USB مش ممنوح")
        DebugLog.d("الإذن تمام، بفتح الاتصال...")
        return printer.open(dev)
    }

    /** يطبع ملف PDF كامل. */
    fun printPdf(
        ctx: Context,
        pfd: ParcelFileDescriptor,
        copies: Int,
        isCancelled: () -> Boolean = { false },
        onProgress: (page: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        val photo = isPhotoMode(ctx)
        val renderer = PdfRenderer(pfd)
        try {
            val total = renderer.pageCount
            openPrinter(ctx).use { session ->
                session.write(PclEncoder.jobStart())

                val canvas = Bitmap.createBitmap(
                    PclEncoder.CANVAS_W, PclEncoder.CANVAS_H, Bitmap.Config.ARGB_8888
                )
                val cache = if (copies > 1) ArrayList<ByteArray>() else null

                for (i in 0 until total) {
                    if (isCancelled()) break
                    onProgress(i + 1, total)

                    canvas.eraseColor(Color.WHITE)
                    renderer.openPage(i).use { page ->
                        val scale = minOf(
                            PclEncoder.CANVAS_W.toFloat() / page.width,
                            PclEncoder.CANVAS_H.toFloat() / page.height
                        )
                        val dx = (PclEncoder.CANVAS_W - page.width * scale) / 2f
                        val dy = (PclEncoder.CANVAS_H - page.height * scale) / 2f
                        val m = Matrix().apply {
                            postScale(scale, scale)
                            postTranslate(dx, dy)
                        }
                        page.render(canvas, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    }

                    val bytes = PclEncoder.encodePage(canvas, photo)
                    session.write(bytes)
                    cache?.add(bytes)
                }
                canvas.recycle()

                if (cache != null && !isCancelled()) {
                    repeat(copies - 1) {
                        for (b in cache) {
                            if (isCancelled()) return@repeat
                            session.write(b)
                        }
                    }
                }
                session.write(PclEncoder.jobEnd())
            }
        } finally {
            renderer.close()
            try { pfd.close() } catch (_: Exception) {}
        }
    }

    /** يبعت نص عادي للطابعة (اختبار بسيط من غير raster). */
    fun printRawText(ctx: Context, text: String) {
        openPrinter(ctx).use { session ->
            val esc = 0x1B.toChar()
            session.write("${esc}E".toByteArray(Charsets.ISO_8859_1))
            session.write(text.toByteArray(Charsets.ISO_8859_1))
            session.write(byteArrayOf(0x0C))
            session.write("${esc}E".toByteArray(Charsets.ISO_8859_1))
        }
    }

    /** يطبع صفحة واحدة جاهزة كـ Bitmap بحجم CANVAS (للاختبار). */
    fun printBitmap(ctx: Context, canvas: Bitmap) {
        val photo = isPhotoMode(ctx)
        openPrinter(ctx).use { session ->
            session.write(PclEncoder.jobStart())
            DebugLog.d("بحوّل الصفحة لـ PCL...")
            val page = PclEncoder.encodePage(canvas, photo)
            DebugLog.d("حجم الصفحة ${page.size} بايت")
            session.write(page)
            session.write(PclEncoder.jobEnd())
        }
    }
}
