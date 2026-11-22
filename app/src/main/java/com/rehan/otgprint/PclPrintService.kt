package com.rehan.otgprint

import android.print.PrintAttributes
import android.print.PrintJobId
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import java.util.concurrent.ConcurrentHashMap

/**
 * خدمة طباعة: بتخلي الطابعة تظهر في قائمة Print في أي تطبيق (Word, Chrome, المعرض ...).
 */
class PclPrintService : PrintService() {

    private val cancelled = ConcurrentHashMap.newKeySet<PrintJobId>()

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = Session()

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        cancelled.add(printJob.id)
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        printJob.start()
        val id = printJob.id

        Thread {
            try {
                val pfd = printJob.document.data
                    ?: throw IllegalStateException("مفيش مستند")
                val copies = maxOf(1, printJob.info.copies)

                PrintEngine.printPdf(
                    ctx = applicationContext,
                    pfd = pfd,
                    copies = copies,
                    isCancelled = { cancelled.contains(id) }
                )

                if (!cancelled.contains(id)) printJob.complete()
            } catch (e: Exception) {
                if (!printJob.isCancelled) printJob.fail(e.message ?: "فشلت الطباعة")
            } finally {
                cancelled.remove(id)
            }
        }.start()
    }

    private inner class Session : PrinterDiscoverySession() {

        private val printerId: PrinterId get() = generatePrinterId("usb-pcl-printer")

        private fun publish() {
            val found = try { UsbPrinter(applicationContext).findDevice() != null } catch (_: Exception) { false }

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
