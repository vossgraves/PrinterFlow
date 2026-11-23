package com.example.printer

import com.example.data.local.DiagnosticLogDao
import com.example.data.local.DiagnosticLogEntity
import com.example.data.local.JobStatus
import com.example.data.local.PrintJobDao
import com.example.data.local.PrintJobEntity
import com.example.data.local.PrinterDao
import com.example.data.local.PrinterEntity
import com.example.data.local.TransmitFormat
import com.example.data.local.UserPreferencesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent Room-backed Print Spooler with real Pause, Resume, Unblock & Retry,
 * automatic format negotiation (PWG Raster / JPEG / PDF), and Wi-Fi <-> Mobile Data fallback.
 */
class PrintSpoolerEngine(
    private val printerDao: PrinterDao,
    private val jobDao: PrintJobDao,
    private val logDao: DiagnosticLogDao,
    private val prefsStore: UserPreferencesStore,
    private val networkGuard: NetworkGuardMonitor,
    private val ippEngine: IppProtocolEngine,
    private val rasterizer: DocumentRasterizer
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobCoroutines = ConcurrentHashMap<Long, Job>()

    fun startWatchingNetworkFallback() {
        scope.launch {
            var lastHadWifi = networkGuard.state.value.hasWifi
            networkGuard.state.collect { netState ->
                val hasWifiNow = netState.hasWifi
                if (!hasWifiNow && lastHadWifi) {
                    // Accidental switch from Wi-Fi to Mobile Data (or Offline)!
                    pauseActiveJobsForWifiLoss()
                } else if (hasWifiNow && !lastHadWifi) {
                    val prefs = prefsStore.preferences.first()
                    if (prefs.autoResumeOnWifiReturn) {
                        resumeWifiPausedJobs()
                    }
                }
                lastHadWifi = hasWifiNow
            }
        }
    }

    suspend fun log(tag: String, message: String, isError: Boolean = false) {
        logDao.insert(DiagnosticLogEntity(tag = tag, message = message, isError = isError))
    }

    suspend fun enqueueJob(job: PrintJobEntity, startPaused: Boolean = false): Long {
        val net = networkGuard.state.value
        val initialStatus = when {
            startPaused -> JobStatus.PAUSED_BY_USER
            !net.hasWifi -> JobStatus.PAUSED_NO_WIFI
            else -> JobStatus.QUEUED
        }
        val detail = when (initialStatus) {
            JobStatus.PAUSED_BY_USER -> "Added paused — tap Resume whenever ready"
            JobStatus.PAUSED_NO_WIFI -> "On Mobile Data / Offline — safely parked until Wi-Fi returns"
            else -> "Queued for transmission"
        }
        val id = jobDao.insert(job.copy(status = initialStatus.name, statusDetail = detail))
        log("SPOOL", "Queued '${job.documentName}' (${job.pageCount}p) -> ${initialStatus.name}")

        if (initialStatus == JobStatus.QUEUED) {
            dispatchJob(id)
        }
        return id
    }

    fun dispatchJob(jobId: Long) {
        if (activeJobCoroutines[jobId]?.isActive == true) return
        val runner = scope.launch {
            try {
                executeJob(jobId)
            } finally {
                activeJobCoroutines.remove(jobId)
            }
        }
        activeJobCoroutines[jobId] = runner
    }

    suspend fun pauseJob(jobId: Long) {
        activeJobCoroutines[jobId]?.cancel()
        activeJobCoroutines.remove(jobId)
        val job = jobDao.getJobById(jobId) ?: return
        jobDao.updateStatus(
            id = jobId,
            status = JobStatus.PAUSED_BY_USER.name,
            detail = "Paused by user (local spool preserved)"
        )
        log("QUEUE", "Paused job #$jobId '${job.documentName}'")

        // Also send IPP Hold-Job if the printer already accepted an IPP job ID
        if (job.ippJobId > 0) {
            val printer = printerDao.getPrinterById(job.printerId) ?: return
            val prefs = prefsStore.preferences.first()
            val client = networkGuard.createBoundHttpClient(prefs.forceWifiSocketBinding, connectTimeoutSec = 3, readTimeoutSec = 5)
            ippEngine.sendJobControl(client, printer, IppProtocolEngine.OP_HOLD_JOB, job.ippJobId)
                .onSuccess { log("IPP", "Hold-Job #${job.ippJobId} -> ${it.statusName}") }
        }
    }

    suspend fun resumeJob(jobId: Long) {
        val job = jobDao.getJobById(jobId) ?: return
        val net = networkGuard.state.value
        if (!net.hasWifi) {
            jobDao.updateStatus(
                id = jobId,
                status = JobStatus.PAUSED_NO_WIFI.name,
                detail = "Waiting for Wi-Fi (currently on ${if (net.hasCellular) "Mobile Data" else "Offline"})"
            )
            log("NET", "Cannot resume #${job.id}: Wi-Fi not connected", isError = true)
            return
        }

        // If the job was held on the printer via IPP, attempt Release-Job & Resume-Printer first
        val printer = printerDao.getPrinterById(job.printerId)
        if (printer != null) {
            val prefs = prefsStore.preferences.first()
            val client = networkGuard.createBoundHttpClient(prefs.forceWifiSocketBinding, connectTimeoutSec = 4, readTimeoutSec = 6)
            if (job.jobStatus == JobStatus.BLOCKED_PRINTER_ERROR) {
                ippEngine.sendPrinterControl(client, printer, IppProtocolEngine.OP_RESUME_PRINTER)
                    .onSuccess { log("IPP", "Resume-Printer sent before retry -> ${it.statusName}") }
            }
            if (job.ippJobId > 0) {
                val rel = ippEngine.sendJobControl(client, printer, IppProtocolEngine.OP_RELEASE_JOB, job.ippJobId)
                if (rel.isSuccess && rel.getOrNull()?.success == true) {
                    jobDao.updateStatus(
                        id = jobId,
                        status = JobStatus.PRINTING_ON_DEVICE.name,
                        detail = "Released held IPP job #${job.ippJobId} on printer"
                    )
                    log("IPP", "Released IPP job #${job.ippJobId}")
                    return
                }
            }
        }

        jobDao.updateStatus(
            id = jobId,
            status = JobStatus.QUEUED.name,
            detail = "Resuming from local spool...",
            blockReason = null
        )
        log("QUEUE", "Resuming job #$jobId '${job.documentName}'")
        dispatchJob(jobId)
    }

    suspend fun cancelJob(jobId: Long) {
        activeJobCoroutines[jobId]?.cancel()
        activeJobCoroutines.remove(jobId)
        val job = jobDao.getJobById(jobId) ?: return

        if (job.ippJobId > 0) {
            val printer = printerDao.getPrinterById(job.printerId)
            if (printer != null) {
                val prefs = prefsStore.preferences.first()
                val client = networkGuard.createBoundHttpClient(prefs.forceWifiSocketBinding, connectTimeoutSec = 3, readTimeoutSec = 5)
                ippEngine.sendJobControl(client, printer, IppProtocolEngine.OP_CANCEL_JOB, job.ippJobId)
                    .onSuccess { log("IPP", "Cancel-Job #${job.ippJobId} -> ${it.statusName}") }
            }
        }

        jobDao.updateStatus(
            id = jobId,
            status = JobStatus.CANCELLED.name,
            detail = "Cancelled by user"
        )
        log("QUEUE", "Cancelled job #$jobId '${job.documentName}'")
    }

    suspend fun pauseAllActive() {
        val active = jobDao.getJobsByStatuses(
            listOf(JobStatus.QUEUED.name, JobStatus.RASTERIZING.name, JobStatus.SENDING.name)
        )
        active.forEach { pauseJob(it.id) }
    }

    suspend fun resumeAllPausedOrBlocked() {
        val paused = jobDao.getJobsByStatuses(
            listOf(
                JobStatus.PAUSED_BY_USER.name,
                JobStatus.PAUSED_NO_WIFI.name,
                JobStatus.BLOCKED_PRINTER_ERROR.name
            )
        )
        paused.forEach { resumeJob(it.id) }
    }

    private suspend fun pauseActiveJobsForWifiLoss() {
        val active = jobDao.getJobsByStatuses(
            listOf(JobStatus.QUEUED.name, JobStatus.RASTERIZING.name, JobStatus.SENDING.name)
        )
        if (active.isEmpty()) return
        log("NET", "Wi-Fi lost (switched to Mobile Data/Offline) — auto-pausing ${active.size} active print(s)", isError = true)
        active.forEach { job ->
            activeJobCoroutines[job.id]?.cancel()
            activeJobCoroutines.remove(job.id)
            jobDao.updateStatus(
                id = job.id,
                status = JobStatus.PAUSED_NO_WIFI.name,
                detail = "Auto-paused on Mobile Data fallback — will resume on Wi-Fi"
            )
        }
    }

    private suspend fun resumeWifiPausedJobs() {
        val parked = jobDao.getJobsByStatuses(listOf(JobStatus.PAUSED_NO_WIFI.name))
        if (parked.isEmpty()) return
        log("NET", "Wi-Fi restored — auto-resuming ${parked.size} parked print job(s)")
        delay(900)
        parked.forEach { resumeJob(it.id) }
    }

    private suspend fun executeJob(jobId: Long) {
        val job = jobDao.getJobById(jobId) ?: return
        val printer = printerDao.getPrinterById(job.printerId) ?: printerDao.getSelectedPrinter()
        if (printer == null) {
            jobDao.updateStatus(
                id = jobId,
                status = JobStatus.BLOCKED_PRINTER_ERROR.name,
                detail = "No target printer selected",
                blockReason = "Select or add your HP Ink Tank 410/419 in Printers tab, then tap Unblock."
            )
            return
        }

        val spoolFile = File(job.localSpoolPath)
        if (!spoolFile.exists()) {
            jobDao.updateStatus(
                id = jobId,
                status = JobStatus.BLOCKED_PRINTER_ERROR.name,
                detail = "Spool file missing",
                blockReason = "Cached file was cleared. Re-select document to print."
            )
            return
        }

        if (!networkGuard.state.value.hasWifi) {
            jobDao.updateStatus(
                id = jobId,
                status = JobStatus.PAUSED_NO_WIFI.name,
                detail = "Switched to Mobile Data — parked safely until Wi-Fi returns"
            )
            return
        }

        val prefs = prefsStore.preferences.first()
        val client = networkGuard.createBoundHttpClient(prefs.forceWifiSocketBinding)

        try {
            // Step 1: Negotiate supported formats from printer (or use cached)
            jobDao.updateStatus(jobId, JobStatus.RASTERIZING.name, "Negotiating IPP format with ${printer.name}...")
            val attrs = ippEngine.getPrinterAttributes(client, printer).getOrNull()
            val formats = attrs?.supportedFormats?.takeIf { it.isNotEmpty() } ?: printer.supportedFormats

            if (attrs != null && attrs.printerState == 5) {
                val reasons = attrs.printerStateReasons.joinToString(", ").ifBlank { "printer-stopped" }
                jobDao.updateStatus(
                    id = jobId,
                    status = JobStatus.BLOCKED_PRINTER_ERROR.name,
                    detail = "Printer reports STOPPED ($reasons)",
                    blockReason = "Printer hardware is paused/blocked ($reasons). Check paper/door/ink valve and tap Unblock & Retry."
                )
                log("IPP", "Job #$jobId blocked: printer-state=stopped ($reasons)", isError = true)
                return
            }

            val chosenMime = resolveBestMimeType(job, formats)
            log("RASTER", "Job #$jobId format negotiated: $chosenMime (source=${job.mimeType})")

            // Step 2: Prepare payload (PWG Raster / JPEG / Raw PDF)
            if (chosenMime == "image/pwg-raster") {
                jobDao.updateStatus(jobId, JobStatus.RASTERIZING.name, "Rendering PWG Raster pages for 410/419...")
                val pwgBytes = rasterizer.encodeToPwgRaster(
                    localPath = job.localSpoolPath,
                    mimeType = job.mimeType,
                    paperSize = job.parsedPaperSize,
                    colorMode = job.parsedColorMode,
                    dpi = when (job.parsedQuality.ippCode) {
                        5 -> 300
                        3 -> 150
                        else -> 200
                    }
                ) { page, total ->
                    scope.launch {
                        jobDao.updateProgress(jobId, page, "Rasterizing page $page of $total (PWG Raster)...")
                    }
                }
                sendPayloadOverIpp(client, printer, job, chosenMime, pwgBytes)
            } else if (chosenMime == "application/pdf" && job.mimeType.contains("pdf", ignoreCase = true)) {
                jobDao.updateStatus(jobId, JobStatus.SENDING.name, "Streaming native PDF to ${printer.host}:631...")
                val pdfBytes = spoolFile.readBytes()
                sendPayloadOverIpp(client, printer, job, "application/pdf", pdfBytes)
            } else {
                // Direct JPEG per page (universally accepted by HP Ink Tank 410/419!)
                val totalPages = job.pageCount.coerceAtLeast(1)
                for (p in 0 until totalPages) {
                    val fresh = jobDao.getJobById(jobId) ?: return
                    if (fresh.jobStatus != JobStatus.QUEUED &&
                        fresh.jobStatus != JobStatus.RASTERIZING &&
                        fresh.jobStatus != JobStatus.SENDING
                    ) {
                        return
                    }
                    jobDao.updateProgress(jobId, p + 1, "Encoding & sending page ${p + 1} of $totalPages (JPEG)...")
                    val jpgBytes = rasterizer.encodePageToJpeg(
                        localPath = job.localSpoolPath,
                        mimeType = job.mimeType,
                        pageIndex = p,
                        paperSize = job.parsedPaperSize,
                        colorMode = job.parsedColorMode
                    )
                    val ok = sendPayloadOverIpp(
                        client = client,
                        printer = printer,
                        job = job,
                        mime = "image/jpeg",
                        bytes = jpgBytes,
                        markCompleted = (p == totalPages - 1)
                    )
                    if (!ok) return
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            if (!networkGuard.state.value.hasWifi) {
                jobDao.updateStatus(
                    id = jobId,
                    status = JobStatus.PAUSED_NO_WIFI.name,
                    detail = "Wi-Fi dropped mid-transfer — safely paused for auto-resume"
                )
            } else {
                val msg = ex.message ?: "Network timeout reaching ${printer.host}:${printer.port}"
                jobDao.updateStatus(
                    id = jobId,
                    status = JobStatus.BLOCKED_PRINTER_ERROR.name,
                    detail = "Blocked: transfer interrupted",
                    blockReason = "$msg. Verify printer IP (${printer.host}) & Wi-Fi Direct/LAN, then tap Unblock & Retry."
                )
                log("IPP", "Job #$jobId blocked: $msg", isError = true)
            }
        }
    }

    private suspend fun sendPayloadOverIpp(
        client: okhttp3.OkHttpClient,
        printer: PrinterEntity,
        job: PrintJobEntity,
        mime: String,
        bytes: ByteArray,
        markCompleted: Boolean = true
    ): Boolean {
        jobDao.updateStatus(
            id = job.id,
            status = JobStatus.SENDING.name,
            detail = "Sending ${(bytes.size / 1024).coerceAtLeast(1)} KB ($mime) to ${printer.host}..."
        )

        val result = ippEngine.printJob(
            client = client,
            printer = printer,
            jobName = job.documentName,
            documentMimeType = mime,
            documentBytes = bytes,
            copies = job.copies,
            colorMode = job.parsedColorMode,
            quality = job.parsedQuality,
            paperSize = job.parsedPaperSize
        )

        return result.fold(
            onSuccess = { ippRes ->
                if (ippRes.success) {
                    val updated = jobDao.getJobById(job.id)
                    if (updated != null) {
                        jobDao.update(
                            updated.copy(
                                ippJobId = ippRes.jobId,
                                currentPage = updated.pageCount,
                                status = if (markCompleted) JobStatus.COMPLETED.name else JobStatus.SENDING.name,
                                statusDetail = if (markCompleted) {
                                    "Accepted by ${printer.name} (IPP #${ippRes.jobId.takeIf { it > 0 } ?: "OK"})"
                                } else {
                                    "Page accepted by printer..."
                                },
                                blockReason = null,
                                updatedAtMs = System.currentTimeMillis()
                            )
                        )
                    }
                    log("IPP", "Print-Job '${job.documentName}' succeeded (${ippRes.statusName}, jobId=${ippRes.jobId})")
                    true
                } else {
                    val hint = if (ippRes.statusCode == 0x040A) {
                        "Printer rejected '$mime'. Switch format to PWG Raster or Direct JPEG and tap Unblock & Retry."
                    } else {
                        "Printer replied ${ippRes.statusName}: ${ippRes.message}"
                    }
                    jobDao.updateStatus(
                        id = job.id,
                        status = JobStatus.BLOCKED_PRINTER_ERROR.name,
                        detail = "Blocked by printer (${ippRes.statusName})",
                        blockReason = hint
                    )
                    log("IPP", "Print-Job rejected: ${ippRes.statusName} (${ippRes.message})", isError = true)
                    false
                }
            },
            onFailure = { err ->
                throw err
            }
        )
    }

    private fun resolveBestMimeType(job: PrintJobEntity, supported: List<String>): String {
        val explicit = job.parsedTransmitFormat.mimeType
        if (explicit != null) return explicit

        val isPdf = job.mimeType.contains("pdf", ignoreCase = true)
        return when {
            isPdf && supported.any { it.equals("application/pdf", ignoreCase = true) } -> "application/pdf"
            supported.any { it.equals("image/pwg-raster", ignoreCase = true) } -> "image/pwg-raster"
            supported.any { it.equals("image/jpeg", ignoreCase = true) } -> "image/jpeg"
            else -> "image/pwg-raster"
        }
    }
}
