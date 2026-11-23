package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class JobStatus {
    QUEUED,
    RASTERIZING,
    SENDING,
    PRINTING_ON_DEVICE,
    PAUSED_BY_USER,
    PAUSED_NO_WIFI,
    BLOCKED_PRINTER_ERROR,
    COMPLETED,
    CANCELLED;

    val isActiveOrPending: Boolean
        get() = this == QUEUED || this == RASTERIZING || this == SENDING || this == PRINTING_ON_DEVICE

    val isPausedOrBlocked: Boolean
        get() = this == PAUSED_BY_USER || this == PAUSED_NO_WIFI || this == BLOCKED_PRINTER_ERROR
}

enum class PrintColorMode(val label: String, val ippValue: String) {
    COLOR("Color (CMYK)", "color"),
    MONOCHROME("Black Ink Only", "monochrome")
}

enum class PrintQuality(val label: String, val ippCode: Int) {
    DRAFT("Draft (Fast)", 3),
    NORMAL("Normal", 4),
    BEST("Best (Photo)", 5)
}

enum class PaperSize(
    val label: String,
    val ippMedia: String,
    val widthPx300Dpi: Int,
    val heightPx300Dpi: Int
) {
    A4("A4 (210×297 mm)", "iso_a4_210x297mm", 2480, 3508),
    LETTER("US Letter", "na_letter_8.5x11in", 2550, 3300),
    PHOTO_4X6("4×6 Photo", "na_index-4x6_4x6in", 1200, 1800)
}

enum class TransmitFormat(val label: String, val mimeType: String?) {
    AUTO_NEGOTIATE("Auto (Best for 410/419)", null),
    PWG_RASTER("PWG Raster (Safe PDF)", "image/pwg-raster"),
    JPEG_DIRECT("Direct JPEG", "image/jpeg"),
    PDF_DIRECT("Raw PDF (If Supported)", "application/pdf")
}

@Entity(tableName = "printers")
data class PrinterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 631,
    val ippPath: String = "/ipp/print",
    val modelHint: String = "HP Ink Tank Wireless 410/419",
    val isWiFiDirect: Boolean = false,
    val isSelected: Boolean = false,
    val lastSeenMs: Long = 0L,
    val supportedFormatsCsv: String = "image/pwg-raster,image/urf,image/jpeg",
    val firmwareVersion: String = "",
    val serialNumber: String = ""
) {
    val ippHttpUrl: String
        get() = "http://$host:$port${if (ippPath.startsWith("/")) ippPath else "/$ippPath"}"

    val ippUri: String
        get() = "ipp://$host:$port${if (ippPath.startsWith("/")) ippPath else "/$ippPath"}"

    val ewsBaseUrl: String
        get() = "http://$host"

    val supportedFormats: List<String>
        get() = supportedFormatsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

@Entity(tableName = "print_jobs")
data class PrintJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val printerId: Long,
    val printerName: String,
    val documentName: String,
    val mimeType: String,
    val localSpoolPath: String,
    val fileSizeBytes: Long,
    val pageCount: Int = 1,
    val currentPage: Int = 0,
    val copies: Int = 1,
    val colorMode: String = PrintColorMode.COLOR.name,
    val quality: String = PrintQuality.NORMAL.name,
    val paperSize: String = PaperSize.A4.name,
    val transmitFormat: String = TransmitFormat.AUTO_NEGOTIATE.name,
    val status: String = JobStatus.QUEUED.name,
    val statusDetail: String = "Ready in local spooler",
    val blockReason: String? = null,
    val ippJobId: Int = -1,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val retryCount: Int = 0
) {
    val jobStatus: JobStatus
        get() = runCatching { JobStatus.valueOf(status) }.getOrDefault(JobStatus.QUEUED)

    val parsedColorMode: PrintColorMode
        get() = runCatching { PrintColorMode.valueOf(colorMode) }.getOrDefault(PrintColorMode.COLOR)

    val parsedQuality: PrintQuality
        get() = runCatching { PrintQuality.valueOf(quality) }.getOrDefault(PrintQuality.NORMAL)

    val parsedPaperSize: PaperSize
        get() = runCatching { PaperSize.valueOf(paperSize) }.getOrDefault(PaperSize.A4)

    val parsedTransmitFormat: TransmitFormat
        get() = runCatching { TransmitFormat.valueOf(transmitFormat) }
            .getOrDefault(TransmitFormat.AUTO_NEGOTIATE)
}

@Entity(tableName = "diagnostic_logs")
data class DiagnosticLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String,
    val isError: Boolean = false
)
