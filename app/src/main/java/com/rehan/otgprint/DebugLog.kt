package com.rehan.otgprint

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** سجل بسيط بيظهر على شاشة التطبيق عشان نعرف إيه اللي بيحصل. */
object DebugLog {
    private val sb = StringBuilder()
    @Volatile var onChange: (() -> Unit)? = null

    @Synchronized
    fun d(msg: String) {
        sb.append(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
            .append("  ").append(msg).append('\n')
        onChange?.invoke()
    }

    @Synchronized
    fun text(): String = sb.toString()
}
