package com.rehan.otgprint

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintJobId
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * خدمة طباعة: بتخلي الطابعة تظهر في قائمة Print في أي تطبيق (Word, Chrome, المعرض ...).
 */
class PclPrintService : PrintService() {

    private val cancelled = ConcurrentHashMap.newKeySet<PrintJobId>()

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(applicationContext)
        DebugLog.d("[خدمة الطباعة] اشتغلت")
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        DebugLog.d("[خدمة الطباعة] بدأ البحث عن الطابعات")
        return Session()
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        DebugLog.d("[خدمة الطباعة] طلب إلغاء مهمة")
        cancelled.add(printJob.id)
        printJob.cancel()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onPrintJobQueued(printJob: PrintJob) {
        DebugLog.init(applicationContext)
        DebugLog.d("[خدمة الطباعة] وصلت مهمة طباعة")

        // كل التعامل مع PrintJob لازم يكون على الـ main thread
        val id = printJob.id
        val src: ParcelFileDescriptor?
        val copies: Int
        try {
            printJob.start()
            src = printJob.document.data
            copies = maxOf(1, printJob.info.copies)
        } catch (e: Throwable) {
            DebugLog.d("[خدمة الطباعة] فشلت: $e")
            try { printJob.fail(e.message ?: "فشلت الطباعة") } catch (_: Throwable) {}
            return
        }
        if (src == null) {
            DebugLog.d("[خدمة الطباعة] فشلت: مفيش مستند في المهمة")
            printJob.fail("مفيش مستند في المهمة")
            return
        }
        DebugLog.d("[خدمة الطباعة] عدد النسخ $copies")

        Thread {
            var tmp: File? = null
            try {
                // ننسخ المستند لملف مؤقت عشان PdfRenderer محتاج ملف seekable
                tmp = File(cacheDir, "job-${System.currentTimeMillis()}.pdf")
                ParcelFileDescriptor.AutoCloseInputStream(src).use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                DebugLog.d("[خدمة الطباعة] حجم الملف ${tmp.length()} بايت")

                val local = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
                PrintEngine.printPdf(
                    ctx = applicationContext,
                    pfd = local,
                    copies = copies,
                    isCancelled = { cancelled.contains(id) },
                    onProgress = { page, total -> DebugLog.d("[خدمة الطباعة] صفحة $page من $total") }
                )

                if (!cancelled.contains(id)) {
                    DebugLog.d("[خدمة الطباعة] المهمة خلصت ✔")
                    mainHandler.post { try { printJob.complete() } catch (_: Throwable) {} }
                }
            } catch (e: Throwable) {
                DebugLog.d("[خدمة الطباعة] فشلت: $e")
                if (!cancelled.contains(id)) {
                    mainHandler.post { try { printJob.fail(e.message ?: "فشلت الطباعة") } catch (_: Throwable) {} }
                }
            } finally {
                cancelled.remove(id)
                try { tmp?.delete() } catch (_: Throwable) {}
            }
        }.start()
    }

    private inner class Session : PrinterDiscoverySession() {

        private val printerId: PrinterId get() = generatePrinterId("usb-pcl-printer")

        private fun publish() {
            val found = try { UsbPrinter(applicationContext).findDevice() != null } catch (_: Exception) { false }
            DebugLog.d("[خدمة الطباعة] الطابعة ${if (found) "متوصلة" else "مش متوصلة"}")

            val caps = PrinterCapabilitiesInfo.Builder(printerId)
                .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                .addResolution(PrintAttributes.Resolution("r300", "300 dpi", 300, 300), true)
                .setColorModes(
                    PrintAttributes.COLOR_MODE_MONOCHROME,
                    PrintAttributes.COLOR_MODE_MONOCHROME
                )
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()

            val info = PrinterInfo.Builder(
                printerId,
                "USB LaserJet (PCL)",
                if (found) PrinterInfo.STATUS_IDLE else PrinterInfo.STATUS_UNAVAILABLE
            ).setCapabilities(caps).build()

            addPrinters(listOf(info))
        }

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) = publish()
        override fun onStopPrinterDiscovery() {}
        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) = publish()
        override fun onStartPrinterStateTracking(printerId: PrinterId) = publish()
        override fun onStopPrinterStateTracking(printerId: PrinterId) {}
        override fun onDestroy() {}
    }
}
