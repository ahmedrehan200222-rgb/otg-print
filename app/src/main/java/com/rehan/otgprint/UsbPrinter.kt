package com.rehan.otgprint

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class UsbPrinter(private val ctx: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.rehan.otgprint.USB_PERMISSION"
        private const val CHUNK = 16 * 1024
        private const val TIMEOUT_MS = 15000
    }

    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    /** أول جهاز USB من فئة الطابعات (Class 7). */
    fun findDevice(): UsbDevice? =
        usb.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_PRINTER }
        }

    fun hasPermission(d: UsbDevice) = usb.hasPermission(d)

    /** يطلب إذن USB وينتظر رد المستخدم (لازم يتنادى من Thread خلفي). */
    fun requestPermissionBlocking(d: UsbDevice, timeoutMs: Long = 30000): Boolean {
        if (usb.hasPermission(d)) return true

        val latch = CountDownLatch(1)
        val granted = AtomicBoolean(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == ACTION_USB_PERMISSION) {
                    granted.set(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    latch.countDown()
                }
            }
        }
        ContextCompat.registerReceiver(
            ctx, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName), flags
            )
            usb.requestPermission(d, pi)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
        return granted.get() || usb.hasPermission(d)
    }

    /** يفتح اتصال بالطابعة ويرجّع Session للكتابة. */
    fun open(d: UsbDevice): Session {
        DebugLog.d("جهاز VID=%04X PID=%04X عدد الـ interfaces=%d".format(d.vendorId, d.productId, d.interfaceCount))
        val candidates = ArrayList<UsbInterface>()
        for (i in 0 until d.interfaceCount) {
            val itf = d.getInterface(i)
            if (itf.interfaceClass == UsbConstants.USB_CLASS_PRINTER) candidates.add(itf)
        }
        // نفضّل Bidirectional (2) ثم Unidirectional (1)، ونتجنب IPP-over-USB (4)
        val sorted = candidates.sortedBy {
            when (it.interfaceProtocol) { 2 -> 0; 1 -> 1; else -> 2 }
        }

        for (itf in sorted) {
            DebugLog.d("interface #${itf.id} alt=${itf.alternateSetting} protocol=${itf.interfaceProtocol} endpoints=${itf.endpointCount}")
            var out: UsbEndpoint? = null
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                DebugLog.d("  endpoint 0x%02X type=%d dir=%d maxPacket=%d".format(ep.address, ep.type, ep.direction, ep.maxPacketSize))
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_OUT
                ) { out = ep; break }
            }
            if (out == null) continue

            val conn = usb.openDevice(d) ?: throw IOException("تعذّر فتح الجهاز")
            val claimed = conn.claimInterface(itf, true)
            DebugLog.d("claimInterface = $claimed")
            if (!claimed) {
                conn.close()
                continue
            }
            if (itf.alternateSetting != 0) conn.setInterface(itf)
            return Session(conn, itf, out)
        }
        throw IOException("مفيش Endpoint صالح للطباعة في الجهاز ده")
    }

    class Session(
        private val conn: UsbDeviceConnection,
        private val itf: UsbInterface,
        private val ep: UsbEndpoint
    ) : Closeable {

        fun write(data: ByteArray) {
            var off = 0
            var zeroTries = 0
            while (off < data.size) {
                val n = minOf(CHUNK, data.size - off)
                val r = conn.bulkTransfer(ep, data, off, n, TIMEOUT_MS)
                if (r < 0) throw IOException("فشل الإرسال للطابعة (الطابعة مشغولة أو مفصولة؟)")
                if (r == 0) {
                    if (++zeroTries > 5) throw IOException("الطابعة مش بتستقبل بيانات")
                    continue
                }
                zeroTries = 0
                off += r
            }
            DebugLog.d("اتبعت ${data.size} بايت للطابعة")
        }

        override fun close() {
            try { conn.releaseInterface(itf) } catch (_: Exception) {}
            conn.close()
        }
    }
}
