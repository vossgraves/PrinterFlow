package com.example.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.DiagnosticLogEntity
import com.example.data.local.InkFlowDatabase
import com.example.data.local.InkFlowPreferences
import com.example.data.local.PaperSize
import com.example.data.local.PrintColorMode
import com.example.data.local.PrintJobEntity
import com.example.data.local.PrintQuality
import com.example.data.local.PrinterEntity
import com.example.data.local.TransmitFormat
import com.example.data.local.UserPreferencesStore
import com.example.printer.DiscoveredPrinter
import com.example.printer.DocumentRasterizer
import com.example.printer.HpLedmClient
import com.example.printer.IppProtocolEngine
import com.example.printer.MdnsPrinterDiscovery
import com.example.printer.NetworkGuardMonitor
import com.example.printer.NetworkGuardState
import com.example.printer.PrintSpoolerEngine
import com.example.printer.PrinterTelemetry
import com.example.printer.SpooledFileMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class InkFlowTab {
    QUEUE,
    PRINT,
    DIAGNOSTICS,
    PRINTERS
}

data class ComposerDraft(
    val fileMeta: SpooledFileMeta? = null,
    val previewBitmap: Bitmap? = null,
    val isLoadingFile: Boolean = false,
    val copies: Int = 1,
    val colorMode: PrintColorMode = PrintColorMode.COLOR,
    val quality: PrintQuality = PrintQuality.NORMAL,
    val paperSize: PaperSize = PaperSize.A4,
    val transmitFormat: TransmitFormat = TransmitFormat.AUTO_NEGOTIATE
)

data class InkFlowUiState(
    val selectedTab: InkFlowTab = InkFlowTab.QUEUE,
    val printers: List<PrinterEntity> = emptyList(),
    val jobs: List<PrintJobEntity> = emptyList(),
    val logs: List<DiagnosticLogEntity> = emptyList(),
    val networkGuard: NetworkGuardState = NetworkGuardState(),
    val preferences: InkFlowPreferences = InkFlowPreferences(),
    val telemetry: PrinterTelemetry = PrinterTelemetry(),
    val isProbingPrinter: Boolean = false,
    val isRunningMaintenance: Boolean = false,
    val isScanningMdns: Boolean = false,
    val discoveredPrinters: List<DiscoveredPrinter> = emptyList(),
    val composer: ComposerDraft = ComposerDraft(),
    val diagnosticSheetPreview: Bitmap? = null,
    val snackbarMessage: String? = null
) {
    val selectedPrinter: PrinterEntity?
        get() = printers.firstOrNull { it.isSelected } ?: printers.firstOrNull()

    val activeJobsCount: Int
        get() = jobs.count { it.jobStatus.isActiveOrPending }

    val pausedOrBlockedCount: Int
        get() = jobs.count { it.jobStatus.isPausedOrBlocked }
}

class InkFlowViewModel(application: Application) : AndroidViewModel(application) {

    private val db = InkFlowDatabase.getInstance(application)
    private val printerDao = db.printerDao()
    private val jobDao = db.printJobDao()
    private val logDao = db.diagnosticLogDao()
    private val prefsStore = UserPreferencesStore(application)

    private val networkGuard = NetworkGuardMonitor(application)
    private val ippEngine = IppProtocolEngine()
    private val ledmClient = HpLedmClient(ippEngine)
    private val rasterizer = DocumentRasterizer(application)
    private val mdnsDiscovery = MdnsPrinterDiscovery(application)
    private val spooler = PrintSpoolerEngine(
        printerDao = printerDao,
        jobDao = jobDao,
        logDao = logDao,
        prefsStore = prefsStore,
        networkGuard = networkGuard,
        ippEngine = ippEngine,
        rasterizer = rasterizer
    )

    private val _selectedTab = MutableStateFlow(InkFlowTab.QUEUE)
    private val _telemetry = MutableStateFlow(PrinterTelemetry())
    private val _isProbing = MutableStateFlow(false)
    private val _isRunningMaint = MutableStateFlow(false)
    private val _composer = MutableStateFlow(ComposerDraft())
    private val _diagPreview = MutableStateFlow<Bitmap?>(null)
    private val _snackbar = MutableStateFlow<String?>(null)

    private val coreStreams = combine(
        printerDao.observePrinters(),
        jobDao.observeAllJobs(),
        logDao.observeRecentLogs(),
        networkGuard.state,
        prefsStore.preferences
    ) { printers, jobs, logs, net, prefs ->
        CoreBundle(printers, jobs, logs, net, prefs)
    }

    private val interactiveStreams = combine(
        _selectedTab,
        _telemetry,
        _isProbing,
        _isRunningMaint,
        _composer
    ) { tab, telem, probing, maint, comp ->
        InteractiveBundle(tab, telem, probing, maint, comp)
    }

    private val auxStreams = combine(
        mdnsDiscovery.isScanning,
        mdnsDiscovery.discovered,
        _diagPreview,
        _snackbar
    ) { scanning, discovered, preview, snack ->
        AuxBundle(scanning, discovered, preview, snack)
    }

    val uiState: StateFlow<InkFlowUiState> = combine(
        coreStreams,
        interactiveStreams,
        auxStreams
    ) { core, inter, aux ->
        InkFlowUiState(
            selectedTab = inter.selectedTab,
            printers = core.printers,
            jobs = core.jobs,
            logs = core.logs,
            networkGuard = core.networkGuard,
            preferences = core.preferences,
            telemetry = inter.telemetry,
            isProbingPrinter = inter.isProbing,
            isRunningMaintenance = inter.isRunningMaint,
            isScanningMdns = aux.isScanning,
            discoveredPrinters = aux.discovered,
            composer = inter.composer,
            diagnosticSheetPreview = aux.diagPreview,
            snackbarMessage = aux.snackbar
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = InkFlowUiState()
    )

    init {
        networkGuard.start()
        spooler.startWatchingNetworkFallback()
        viewModelScope.launch {
            if (printerDao.countPrinters() == 0) {
                // Seed HP Ink Tank Wireless 410/419 Wi-Fi Direct standard endpoint so users have a 1-tap target
                printerDao.insert(
                    PrinterEntity(
                        name = "HP Ink Tank 410/419 (Wi-Fi Direct)",
                        host = "192.168.223.1",
                        port = 631,
                        ippPath = "/ipp/print",
                        modelHint = "HP Ink Tank Wireless 410 / 419",
                        isWiFiDirect = true,
                        isSelected = true
                    )
                )
                spooler.log("INIT", "Added default HP Ink Tank 410/419 Wi-Fi Direct profile (192.168.223.1:631)")
            }
        }
    }

    override fun onCleared() {
        mdnsDiscovery.stopScan()
        networkGuard.stop()
        super.onCleared()
    }

    fun selectTab(tab: InkFlowTab) {
        _selectedTab.value = tab
    }

    fun clearSnackbar() {
        _snackbar.value = null
    }

    fun refreshNetworkState() {
        networkGuard.refreshState()
        _snackbar.value = "Network interface refreshed"
    }

    // --- Document Intake & Composer ---

    fun importDocumentUri(uri: Uri, fallbackMime: String? = null, switchToPrintTab: Boolean = true) {
        if (switchToPrintTab) _selectedTab.value = InkFlowTab.PRINT
        _composer.value = _composer.value.copy(isLoadingFile = true)

        viewModelScope.launch {
            val result = rasterizer.ingestUriToSpool(uri, fallbackMime)
            result.fold(
                onSuccess = { meta ->
                    val thumb = rasterizer.renderPreviewThumbnail(meta.localPath, meta.mimeType)
                    _composer.value = _composer.value.copy(
                        fileMeta = meta,
                        previewBitmap = thumb,
                        isLoadingFile = false
                    )
                    spooler.log("FILE", "Cached '${meta.displayName}' (${meta.pageCount}p, ${meta.sizeBytes / 1024} KB) to local spool")
                },
                onFailure = { err ->
                    _composer.value = _composer.value.copy(isLoadingFile = false)
                    _snackbar.value = "Could not load file: ${err.message}"
                    spooler.log("FILE", "Intake failed: ${err.message}", isError = true)
                }
            )
        }
    }

    fun clearComposerDocument() {
        _composer.value = _composer.value.copy(fileMeta = null, previewBitmap = null)
    }

    fun updateComposerCopies(delta: Int) {
        val next = (_composer.value.copies + delta).coerceIn(1, 50)
        _composer.value = _composer.value.copy(copies = next)
    }

    fun updateComposerColorMode(mode: PrintColorMode) {
        _composer.value = _composer.value.copy(colorMode = mode)
    }

    fun updateComposerQuality(quality: PrintQuality) {
        _composer.value = _composer.value.copy(quality = quality)
    }

    fun updateComposerPaperSize(size: PaperSize) {
        _composer.value = _composer.value.copy(paperSize = size)
    }

    fun updateComposerFormat(format: TransmitFormat) {
        _composer.value = _composer.value.copy(transmitFormat = format)
    }

    fun submitComposerJob(startPaused: Boolean) {
        val draft = _composer.value
        val fileMeta = draft.fileMeta ?: run {
            _snackbar.value = "Pick a PDF or photo first"
            return
        }
        val printer = uiState.value.selectedPrinter ?: run {
            _snackbar.value = "Add or select a printer first"
            _selectedTab.value = InkFlowTab.PRINTERS
            return
        }

        viewModelScope.launch {
            spooler.enqueueJob(
                job = PrintJobEntity(
                    printerId = printer.id,
                    printerName = printer.name,
                    documentName = fileMeta.displayName,
                    mimeType = fileMeta.mimeType,
                    localSpoolPath = fileMeta.localPath,
                    fileSizeBytes = fileMeta.sizeBytes,
                    pageCount = fileMeta.pageCount,
                    copies = draft.copies,
                    colorMode = draft.colorMode.name,
                    quality = draft.quality.name,
                    paperSize = draft.paperSize.name,
                    transmitFormat = draft.transmitFormat.name
                ),
                startPaused = startPaused
            )
            _composer.value = ComposerDraft()
            _selectedTab.value = InkFlowTab.QUEUE
            _snackbar.value = if (startPaused) {
                "Added '${fileMeta.displayName}' paused in queue"
            } else {
                "Queued '${fileMeta.displayName}' for ${printer.name}"
            }
        }
    }

    // --- Queue Controls ---

    fun pauseJob(jobId: Long) = viewModelScope.launch { spooler.pauseJob(jobId) }

    fun resumeOrUnblockJob(jobId: Long) = viewModelScope.launch { spooler.resumeJob(jobId) }

    fun cancelJob(jobId: Long) = viewModelScope.launch { spooler.cancelJob(jobId) }

    fun deleteJob(jobId: Long) = viewModelScope.launch { jobDao.deleteById(jobId) }

    fun pauseAllJobs() = viewModelScope.launch {
        spooler.pauseAllActive()
        _snackbar.value = "Paused active queue jobs"
    }

    fun resumeAllJobs() = viewModelScope.launch {
        spooler.resumeAllPausedOrBlocked()
        _snackbar.value = "Resuming paused & blocked jobs"
    }

    fun clearFinishedJobs() = viewModelScope.launch {
        jobDao.clearFinished()
        _snackbar.value = "Cleared completed & cancelled jobs"
    }

    // --- Diagnostics & Maintenance ---

    fun probeSelectedPrinter() {
        val printer = uiState.value.selectedPrinter ?: run {
            _snackbar.value = "No printer selected to probe"
            return
        }
        viewModelScope.launch {
            _isProbing.value = true
            spooler.log("DIAG", "Probing ${printer.name} (${printer.host}:${printer.port})...")
            val prefs = prefsStore.preferences.first()
            val client = networkGuard.createBoundHttpClient(prefs.forceWifiSocketBinding, connectTimeoutSec = 4, readTimeoutSec = 6)
            val sf = networkGuard.getBoundSocketFactory(prefs.forceWifiSocketBinding)

            val result = ledmClient.probePrinterFull(client, sf, printer)
            _telemetry.value = result
            _isProbing.value = false

            if (result.reachable) {
                printerDao.update(
                    printer.copy(
                        lastSeenMs = System.currentTimeMillis(),
                        modelHint = result.modelName.ifBlank { printer.modelHint },
                        firmwareVersion = result.firmwareVersion.ifBlank { printer.firmwareVersion },
                        serialNumber = result.serialNumber.ifBlank { printer.serialNumber },
                        supportedFormatsCsv = result.supportedFormats
                            .takeIf { it.isNotEmpty() }
                            ?.joinToString(",")
                            ?: printer.supportedFormatsCsv
                    )
                )
                spooler.log("DIAG", "Online (${result.latencyMs}ms) | LEDM=${result.ledmStatusCategory} | IPP=${result.ippStateLabel}")
                _snackbar.value = "Printer online (${result.latencyMs} ms)"
            } else {
                spooler.log("DIAG", result.errorMessage ?: "Printer unreachable", isError = true)
                _snackbar.value = "Printer unreachable on ${printer.host}"
            }
        }
    }

    fun printDiagnosticNozzlePage() {
        val printer = uiState.value.selectedPrinter ?: run {
            _snackbar.value = "Select a printer first"
            return
        }
        viewModelScope.launch {
            val meta = rasterizer.createDiagnosticPageSpoolFile(printer.name)
            spooler.enqueueJob(
                job = PrintJobEntity(
                    printerId = printer.id,
                    printerName = printer.name,
                    documentName = meta.displayName,
                    mimeType = meta.mimeType,
                    localSpoolPath = meta.localPath,
                    fileSizeBytes = meta.sizeBytes,
                    pageCount = 1,
                    copies = 1,
                    colorMode = PrintColorMode.COLOR.name,
                    quality = PrintQuality.BEST.name,
                    paperSize = PaperSize.A4.name,
                    transmitFormat = TransmitFormat.JPEG_DIRECT.name
                ),
                startPaused = false
            )
            _selectedTab.value = InkFlowTab.QUEUE
            _snackbar.value = "Diagnostic nozzle & alignment sheet queued"
        }
    }

    fun openDiagnosticSheetPreview() {
        val label = uiState.value.selectedPrinter?.name ?: "HP Ink Tank 410 / 419"
        viewModelScope.launch {
            _diagPreview.value = rasterizer.renderDiagnosticTestSheet(label, width = 900, height = 1272)
        }
    }

    fun closeDiagnosticSheetPreview() {
        _diagPreview.value = null
    }

    fun triggerPrintheadClean(level: Int = 1) {
        val printer = uiState.value.selectedPrinter ?: return
        viewModelScope.launch {
            _isRunningMaint.value = true
            val prefs = prefsStore.preferences.first()
            val client = networkGuard.createBoundHttpClient(prefs.forceWifiSocketBinding, connectTimeoutSec = 5, readTimeoutSec = 10)
            val res = ledmClient.triggerPrintheadClean(client, printer, level)
            _isRunningMaint.value = false
            spooler.log("LEDM", res.message, isError = !res.success)
            _snackbar.value = res.message
        }
    }

    fun triggerPrintheadAlign() {
        val printer = uiState.value.selectedPrinter ?: return
        viewModelScope.launch {
            _isRunningMaint.value = true
            val prefs = prefsStore.preferences.first()
            val client = networkGuard.createBoundHttpClient(prefs.forceWifiSocketBinding, connectTimeoutSec = 5, readTimeoutSec = 10)
            val res = ledmClient.triggerPrintheadAlignment(client, printer)
            _isRunningMaint.value = false
            spooler.log("LEDM", res.message, isError = !res.success)
            _snackbar.value = res.message
        }
    }

    fun sendHardwareResumePrinter() {
        val printer = uiState.value.selectedPrinter ?: return
        viewModelScope.launch {
            _isRunningMaint.value = true
            val prefs = prefsStore.preferences.first()
            val client = networkGuard.createBoundHttpClient(prefs.forceWifiSocketBinding, connectTimeoutSec = 4, readTimeoutSec = 6)
            val res = ippEngine.sendPrinterControl(client, printer, IppProtocolEngine.OP_RESUME_PRINTER)
            _isRunningMaint.value = false
            res.fold(
                onSuccess = {
                    spooler.log("IPP", "Resume-Printer -> ${it.statusName}")
                    _snackbar.value = "Sent Resume-Printer (${it.statusName})"
                },
                onFailure = {
                    spooler.log("IPP", "Resume-Printer failed: ${it.message}", isError = true)
                    _snackbar.value = "Could not reach printer: ${it.message}"
                }
            )
        }
    }

    fun clearDiagnosticLogs() = viewModelScope.launch {
        logDao.clearAll()
    }

    // --- Printers & Network Guard Settings ---

    fun toggleMdnsScan() {
        if (mdnsDiscovery.isScanning.value) {
            mdnsDiscovery.stopScan()
        } else {
            mdnsDiscovery.startScan { msg ->
                viewModelScope.launch { spooler.log("mDNS", msg) }
            }
        }
    }

    fun saveAndSelectDiscovered(item: DiscoveredPrinter) {
        viewModelScope.launch {
            val existing = printerDao.findByHostPort(item.host, item.port)
            printerDao.clearSelected()
            if (existing != null) {
                printerDao.update(existing.copy(isSelected = true, lastSeenMs = System.currentTimeMillis()))
            } else {
                printerDao.insert(
                    PrinterEntity(
                        name = item.serviceName,
                        host = item.host,
                        port = item.port,
                        ippPath = item.ippPath,
                        modelHint = item.model,
                        isWiFiDirect = item.host.startsWith("192.168.223."),
                        isSelected = true,
                        lastSeenMs = System.currentTimeMillis()
                    )
                )
            }
            _snackbar.value = "Selected ${item.serviceName} (${item.host})"
            probeSelectedPrinter()
        }
    }

    fun addManualPrinter(name: String, host: String, port: Int, ippPath: String) {
        val cleanHost = host.trim()
        if (cleanHost.isBlank()) {
            _snackbar.value = "Enter a valid printer IP address"
            return
        }
        viewModelScope.launch {
            printerDao.clearSelected()
            printerDao.insert(
                PrinterEntity(
                    name = name.trim().ifBlank { "HP Ink Tank ($cleanHost)" },
                    host = cleanHost,
                    port = port.coerceIn(1, 65535),
                    ippPath = ippPath.trim().ifBlank { "/ipp/print" },
                    modelHint = "HP Ink Tank Wireless 410 / 419",
                    isWiFiDirect = cleanHost == "192.168.223.1",
                    isSelected = true
                )
            )
            spooler.log("PRINTER", "Added printer '$name' at $cleanHost:$port")
            _snackbar.value = "Saved printer at $cleanHost"
            probeSelectedPrinter()
        }
    }

    fun selectPrinter(printerId: Long) {
        viewModelScope.launch {
            printerDao.clearSelected()
            printerDao.markSelected(printerId)
            _telemetry.value = PrinterTelemetry()
            probeSelectedPrinter()
        }
    }

    fun deletePrinter(printerId: Long) {
        viewModelScope.launch {
            printerDao.deleteById(printerId)
        }
    }

    fun setForceWifiSocketBinding(enabled: Boolean) = viewModelScope.launch {
        prefsStore.setForceWifiBinding(enabled)
        spooler.log("NET", "Force Wi-Fi socket binding = $enabled")
    }

    fun setAutoResumeOnWifi(enabled: Boolean) = viewModelScope.launch {
        prefsStore.setAutoResumeOnWifi(enabled)
        spooler.log("NET", "Auto-resume on Wi-Fi return = $enabled")
    }

    fun updateVisualInkLevels(c: Int, m: Int, y: Int, k: Int) = viewModelScope.launch {
        prefsStore.setVisualInkLevels(c, m, y, k)
    }

    private data class CoreBundle(
        val printers: List<PrinterEntity>,
        val jobs: List<PrintJobEntity>,
        val logs: List<DiagnosticLogEntity>,
        val networkGuard: NetworkGuardState,
        val preferences: InkFlowPreferences
    )

    private data class InteractiveBundle(
        val selectedTab: InkFlowTab,
        val telemetry: PrinterTelemetry,
        val isProbing: Boolean,
        val isRunningMaint: Boolean,
        val composer: ComposerDraft
    )

    private data class AuxBundle(
        val isScanning: Boolean,
        val discovered: List<DiscoveredPrinter>,
        val diagPreview: Bitmap?,
        val snackbar: String?
    )
}
