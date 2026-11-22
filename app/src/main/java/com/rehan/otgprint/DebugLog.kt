package com.rehan.otgprint

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** سجل بسيط بيظهر على شاشة التطبيق، ومتخزن في ملف عشان يفضل حتى لو العملية اتقفلت. */
object DebugLog {
    private const val MAX = 20000
    private val sb = StringBuilder()
    private var file: File? = null
    @Volatile var onChange: (() -> Unit)? = null

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "log.txt")
        file = f
        try {
            if (f.exists()) {
                var t = f.readText()
                if (t.length > MAX) { t = t.takeLast(MAX); f.writeText(t) }
                sb.append(t)
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun d(msg: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + msg + "\n"
        sb.append(line)
        try { file?.appendText(line) } catch (_: Exception) {}
        onChange?.invoke()
    }

    @Synchronized
    fun text(): String = sb.toString()

    @Synchronized
    fun clear() {
        sb.setLength(0)
        try { file?.writeText("") } catch (_: Exception) {}
        onChange?.invoke()
    }
}
