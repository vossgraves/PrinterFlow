package com.example.printer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.example.data.local.PaperSize
import com.example.data.local.PrintColorMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

data class SpooledFileMeta(
    val displayName: String,
    val mimeType: String,
    val localPath: String,
    val sizeBytes: Long,
    val pageCount: Int
)

/**
 * Handles zero-permission document intake into local persistent spool cache,
 * PDF/Image -> PWG Raster (RFC 5102 / PWG 5102.4) & JPEG rasterization for HP Ink Tank 410/419,
 * and generates precision CMYK nozzle check & alignment diagnostic sheets.
 */
class DocumentRasterizer(private val context: Context) {

    private val spoolDir: File
        get() = File(context.filesDir, "spool").apply { if (!exists()) mkdirs() }

    suspend fun ingestUriToSpool(uri: Uri, fallbackMime: String? = null): Result<SpooledFileMeta> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val mime = resolver.getType(uri) ?: fallbackMime ?: "application/octet-stream"
                var displayName = "Document_${System.currentTimeMillis() % 10000}"

                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            c.getString(idx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                        }
                    }
                }

                val ext = when {
                    mime.contains("pdf", ignoreCase = true) -> ".pdf"
                    mime.contains("png", ignoreCase = true) -> ".png"
                    mime.contains("webp", ignoreCase = true) -> ".webp"
                    else -> ".jpg"
                }
                val targetFile = File(spoolDir, "job_${System.currentTimeMillis()}$ext")

                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output, bufferSize = 16 * 1024)
                    }
                } ?: throw IllegalStateException("Unable to read selected document URI")

                val pages = if (mime.contains("pdf", ignoreCase = true) || targetFile.extension == "pdf") {
                    countPdfPages(targetFile)
                } else {
                    1
                }

                SpooledFileMeta(
                    displayName = displayName,
                    mimeType = mime,
                    localPath = targetFile.absolutePath,
                    sizeBytes = targetFile.length(),
                    pageCount = pages.coerceAtLeast(1)
                )
            }
        }

    suspend fun createDiagnosticPageSpoolFile(printerLabel: String): SpooledFileMeta =
        withContext(Dispatchers.IO) {
            val bitmap = renderDiagnosticTestSheet(printerLabel, width = 1240, height = 1754)
            val file = File(spoolDir, "diag_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            bitmap.recycle()
            SpooledFileMeta(
                displayName = "InkFlow 410/419 Nozzle & Alignment Sheet.jpg",
                mimeType = "image/jpeg",
                localPath = file.absolutePath,
                sizeBytes = file.length(),
                pageCount = 1
            )
        }

    suspend fun renderPreviewThumbnail(localPath: String, mimeType: String): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(localPath)
                if (!file.exists()) return@runCatching null
                if (mimeType.contains("pdf", ignoreCase = true) || file.extension == "pdf") {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            if (renderer.pageCount <= 0) return@runCatching null
                            renderer.openPage(0).use { page ->
                                val scale = 600f / maxOf(page.width, page.height).coerceAtLeast(1)
                                val w = (page.width * scale).roundToInt().coerceAtLeast(100)
                                val h = (page.height * scale).roundToInt().coerceAtLeast(100)
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bmp
                            }
                        }
                    }
                } else {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(localPath, opts)
                    val maxDim = maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(1)
                    val sample = (maxDim / 600).coerceAtLeast(1)
                    BitmapFactory.decodeFile(
                        localPath,
                        BitmapFactory.Options().apply { inSampleSize = sample }
                    )
                }
            }.getOrNull()
        }

    /**
     * Renders all pages of a PDF or Image into a single valid PWG Raster (`image/pwg-raster`)
     * byte array (RaS2 header + 1796-byte page headers + PackBits RLE scanlines) that HP Ink Tank
     * 410/419 firmware natively accepts over IPP port 631.
     */
    suspend fun encodeToPwgRaster(
        localPath: String,
        mimeType: String,
        paperSize: PaperSize,
        colorMode: PrintColorMode,
        dpi: Int = 150,
        onPageRendered: (pageIndex: Int, totalPages: Int) -> Unit = { _, _ -> }
    ): ByteArray = withContext(Dispatchers.IO) {
        val file = File(localPath)
        require(file.exists()) { "Spool file missing from disk cache" }

        // Scale target dimensions cleanly to requested DPI (300 DPI reference)
        val dpiScale = dpi / 300f
        val targetW = (paperSize.widthPx300Dpi * dpiScale).roundToInt().coerceAtLeast(300)
        val targetH = (paperSize.heightPx300Dpi * dpiScale).roundToInt().coerceAtLeast(400)

        val baos = ByteArrayOutputStream(64 * 1024)
        // PWG Raster Sync Word: "RaS2"
        baos.write(byteArrayOf('R'.code.toByte(), 'a'.code.toByte(), 'S'.code.toByte(), '2'.code.toByte()))

        if (mimeType.contains("pdf", ignoreCase = true) || file.extension == "pdf") {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val total = renderer.pageCount.coerceAtLeast(1)
                    for (i in 0 until total) {
                        onPageRendered(i + 1, total)
                        renderer.openPage(i).use { page ->
                            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            writePwgRasterPage(baos, bmp, dpi, colorMode)
                            bmp.recycle()
                        }
                    }
                }
            }
        } else {
            onPageRendered(1, 1)
            val src = BitmapFactory.decodeFile(localPath)
                ?: throw IllegalStateException("Cannot decode image file for printing")
            val pageBmp = fitBitmapOnPage(src, targetW, targetH)
            src.recycle()
            writePwgRasterPage(baos, pageBmp, dpi, colorMode)
            pageBmp.recycle()
        }
        baos.toByteArray()
    }

    /**
     * Renders page [pageIndex] of a PDF or Image into a print-ready JPEG byte array.
     */
    suspend fun encodePageToJpeg(
        localPath: String,
        mimeType: String,
        pageIndex: Int,
        paperSize: PaperSize,
        colorMode: PrintColorMode
    ): ByteArray = withContext(Dispatchers.IO) {
        val file = File(localPath)
        require(file.exists()) { "Spool file missing from disk cache" }

        val targetW = (paperSize.widthPx300Dpi * 0.5f).roundToInt()
        val targetH = (paperSize.heightPx300Dpi * 0.5f).roundToInt()

        val pageBmp: Bitmap = if (mimeType.contains("pdf", ignoreCase = true) || file.extension == "pdf") {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val idx = pageIndex.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                    renderer.openPage(idx).use { page ->
                        val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        bmp
                    }
                }
            }
        } else {
            val src = BitmapFactory.decodeFile(localPath)
                ?: throw IllegalStateException("Cannot decode image file")
            val fitted = fitBitmapOnPage(src, targetW, targetH)
            src.recycle()
            fitted
        }

        val finalBmp = if (colorMode == PrintColorMode.MONOCHROME) {
            convertToGrayscale(pageBmp).also { pageBmp.recycle() }
        } else {
            pageBmp
        }

        val out = ByteArrayOutputStream(32 * 1024)
        finalBmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        finalBmp.recycle()
        out.toByteArray()
    }

    /**
     * Generates a crisp, real-world HP Ink Tank 410/419 Print Quality Diagnostic Sheet
     * containing CMYK nozzle staircases, solid tank-flow blocks, alignment crosshairs, and grayscale ramp.
     */
    fun renderDiagnosticTestSheet(
        printerLabel: String,
        width: Int = 900,
        height: Int = 1272
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val margin = width * 0.06f
        val contentW = width - margin * 2f

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = width * 0.036f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#475569")
            textSize = width * 0.019f
            typeface = Typeface.MONOSPACE
        }
        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0055D4")
            textSize = width * 0.022f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            strokeWidth = 2f
        }

        var y = margin + titlePaint.textSize
        canvas.drawText("INKFLOW PRINT QUALITY DIAGNOSTIC", margin, y, titlePaint)
        y += subPaint.textSize * 1.5f
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        canvas.drawText("TARGET: ${printerLabel.take(34)}  |  $stamp", margin, y, subPaint)
        y += width * 0.022f
        canvas.drawLine(margin, y, width - margin, y, linePaint)
        y += width * 0.045f

        // 1. Solid CMYK Ink Flow Blocks (Detect Starvation / Air Bubbles in 410/419 Tubes)
        canvas.drawText("1. CMYK INK TANK FLOW BLOCKS (CHECK FOR WHITE STREAKS)", margin, y, sectionPaint)
        y += width * 0.025f

        val channels = listOf(
            Triple("CYAN (C)", Color.parseColor("#00AEEF"), Color.WHITE),
            Triple("MAGENTA (M)", Color.parseColor("#EC008C"), Color.WHITE),
            Triple("YELLOW (Y)", Color.parseColor("#FFF200"), Color.parseColor("#1E293B")),
            Triple("BLACK (K)", Color.parseColor("#000000"), Color.WHITE)
        )

        val blockGap = width * 0.02f
        val blockW = (contentW - blockGap * 3f) / 4f
        val blockH = height * 0.14f
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = width * 0.018f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        channels.forEachIndexed { idx, (name, fill, txtColor) ->
            val left = margin + idx * (blockW + blockGap)
            fillPaint.color = fill
            canvas.drawRoundRect(RectF(left, y, left + blockW, y + blockH), 14f, 14f, fillPaint)
            badgePaint.color = txtColor
            canvas.drawText(name, left + 12f, y + blockH - 14f, badgePaint)
        }
        y += blockH + width * 0.055f

        // 2. Fine Nozzle Staircase Check (Individual Printhead Jet Inspection)
        canvas.drawText("2. PRINTHEAD NOZZLE CHECK STAIRCASE (MISSING STEPS = CLOGGED JET)", margin, y, sectionPaint)
        y += width * 0.025f

        val nozzlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f }
        val cols = 10
        val stepW = contentW / cols
        val bandH = height * 0.052f

        channels.forEach { (_, color, _) ->
            nozzlePaint.color = color
            for (c in 0 until cols) {
                val sx = margin + c * stepW + 6f
                val ex = margin + (c + 1) * stepW - 6f
                val stepY = y + (c % 5) * (bandH / 5f)
                canvas.drawLine(sx, stepY, ex, stepY, nozzlePaint)
                canvas.drawLine(sx, stepY + 8f, ex, stepY + 8f, nozzlePaint)
            }
            y += bandH + 14f
        }
        y += width * 0.03f

        // 3. Printhead Bi-Directional Alignment Crosshairs & Fine Grid
        canvas.drawText("3. BI-DIRECTIONAL ALIGNMENT GRID (LINES MUST BE STRAIGHT)", margin, y, sectionPaint)
        y += width * 0.028f

        val gridH = height * 0.15f
        val alignPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        canvas.drawRect(margin, y, width - margin, y + gridH, alignPaint)

        val vSteps = 16
        for (i in 1 until vSteps) {
            val gx = margin + (contentW / vSteps) * i
            canvas.drawLine(gx, y, gx, y + gridH, alignPaint)
        }
        val hSteps = 6
        for (j in 1 until hSteps) {
            val gy = y + (gridH / hSteps) * j
            canvas.drawLine(margin, gy, width - margin, gy, alignPaint)
        }
        y += gridH + width * 0.055f

        // 4. Grayscale & Registration Ramp
        canvas.drawText("4. BLACK DENSITY & MICRO-TEXT SHARPNESS", margin, y, sectionPaint)
        y += width * 0.025f

        val rampSteps = 10
        val rampW = contentW / rampSteps
        val rampH = height * 0.055f
        for (i in 0 until rampSteps) {
            val v = (255 - (i * 255 / (rampSteps - 1))).coerceIn(0, 255)
            fillPaint.color = Color.rgb(v, v, v)
            val rx = margin + i * rampW
            canvas.drawRect(rx, y, rx + rampW, y + rampH, fillPaint)
        }
        y += rampH + width * 0.035f
        canvas.drawText(
            "TIP: If color blocks show banding or gaps, check transparent ink tank levels & run Printhead Clean.",
            margin,
            y,
            subPaint
        )

        return bmp
    }

    private fun writePwgRasterPage(
        out: ByteArrayOutputStream,
        bmp: Bitmap,
        dpi: Int,
        colorMode: PrintColorMode
    ) {
        val width = bmp.width
        val height = bmp.height
        val isGray = colorMode == PrintColorMode.MONOCHROME
        val numColors = if (isGray) 1 else 3
        val bitsPerColor = 8
        val bitsPerPixel = bitsPerColor * numColors
        val bytesPerLine = width * numColors
        val colorSpace = if (isGray) 18 else 19 // PWG 18=SGRAY, 19=SRGB

        // PWG Raster 1796-byte big-endian Page Header (PWG 5102.4)
        val headerBytes = ByteArray(1796)
        val hStream = DataOutputStream(object : java.io.OutputStream() {
            var pos = 0
            override fun write(b: Int) {
                if (pos < headerBytes.size) headerBytes[pos++] = b.toByte()
            }
        })
        // Bytes 0..63: MediaClass ("PwgRaster")
        val mediaClass = "PwgRaster".toByteArray()
        for (i in 0 until 64) hStream.writeByte(if (i < mediaClass.size) mediaClass[i].toInt() else 0)
        // Bytes 64..255: MediaColor, MediaType, OutputType (zeroed)
        for (i in 0 until 192) hStream.writeByte(0)
        // Bytes 256..275: AdvanceDistance..InsertSheet (5 ints = 20 bytes)
        repeat(5) { hStream.writeInt(0) }
        // Bytes 276..283: HWResolution[2] (xDpi, yDpi)
        hStream.writeInt(dpi)
        hStream.writeInt(dpi)
        // Bytes 284..371: Margins, MediaPosition, NumCopies, Orientation, PageSize (22 ints = 88 bytes)
        repeat(22) { hStream.writeInt(0) }
        // Bytes 372..375: cupsWidth
        hStream.writeInt(width)
        // Bytes 376..379: cupsHeight
        hStream.writeInt(height)
        // Bytes 380..383: cupsMediaType
        hStream.writeInt(0)
        // Bytes 384..387: cupsBitsPerColor
        hStream.writeInt(bitsPerColor)
        // Bytes 388..391: cupsBitsPerPixel
        hStream.writeInt(bitsPerPixel)
        // Bytes 392..395: cupsBytesPerLine
        hStream.writeInt(bytesPerLine)
        // Bytes 396..399: cupsColorOrder (0 = Chunky)
        hStream.writeInt(0)
        // Bytes 400..403: cupsColorSpace (18=sGray, 19=sRGB)
        hStream.writeInt(colorSpace)
        // Bytes 404..419: cupsCompression..cupsRowStep (4 ints)
        repeat(4) { hStream.writeInt(0) }
        // Bytes 420..423: cupsNumColors
        hStream.writeInt(numColors)
        hStream.flush()
        out.write(headerBytes)

        // Encode raster lines with PWG PackBits RLE (1 byte line-repeat count + run-encoded pixels)
        val rowPixels = IntArray(width)
        val rawLine = ByteArray(bytesPerLine)

        for (y in 0 until height) {
            bmp.getPixels(rowPixels, 0, width, 0, y, width, 1)
            if (isGray) {
                for (x in 0 until width) {
                    val p = rowPixels[x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    rawLine[x] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
                }
            } else {
                var idx = 0
                for (x in 0 until width) {
                    val p = rowPixels[x]
                    rawLine[idx++] = ((p shr 16) and 0xFF).toByte()
                    rawLine[idx++] = ((p shr 8) and 0xFF).toByte()
                    rawLine[idx++] = (p and 0xFF).toByte()
                }
            }
            // Line repeat count = 0 (repeat 1 time)
            out.write(0)
            writePackBitsPixels(out, rawLine, width, numColors)
        }
    }

    private fun writePackBitsPixels(
        out: ByteArrayOutputStream,
        line: ByteArray,
        width: Int,
        bpp: Int
    ) {
        var x = 0
        while (x < width) {
            // Check for repeating pixel run
            var runLen = 1
            while (x + runLen < width && runLen < 128 && pixelsEqual(line, x, x + runLen, bpp)) {
                runLen++
            }
            if (runLen >= 2) {
                out.write(runLen - 1) // 0..127 means repeat next pixel (count + 1) times
                out.write(line, x * bpp, bpp)
                x += runLen
            } else {
                val start = x
                var litLen = 1
                while (start + litLen < width && litLen < 128) {
                    val next = start + litLen
                    if (next + 1 < width && pixelsEqual(line, next, next + 1, bpp)) break
                    litLen++
                }
                // 128..255 means copy (257 - code) literal pixels
                out.write(257 - litLen)
                out.write(line, start * bpp, litLen * bpp)
                x += litLen
            }
        }
    }

    private fun pixelsEqual(line: ByteArray, a: Int, b: Int, bpp: Int): Boolean {
        val ia = a * bpp
        val ib = b * bpp
        for (k in 0 until bpp) {
            if (line[ia + k] != line[ib + k]) return false
        }
        return true
    }

    private fun fitBitmapOnPage(src: Bitmap, pageW: Int, pageH: Int): Bitmap {
        val page = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)
        val margin = (min(pageW, pageH) * 0.03f)
        val availW = pageW - margin * 2f
        val availH = pageH - margin * 2f
        val scale = min(availW / src.width.coerceAtLeast(1), availH / src.height.coerceAtLeast(1))
        val drawW = src.width * scale
        val drawH = src.height * scale
        val left = (pageW - drawW) / 2f
        val top = (pageH - drawH) / 2f
        canvas.drawBitmap(src, null, RectF(left, top, left + drawW, top + drawH), Paint(Paint.FILTER_BITMAP_FLAG))
        return page
    }

    private fun convertToGrayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix().apply { setSaturation(0f) }
            )
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun countPdfPages(file: File): Int {
        return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { it.pageCount }
            }
        }.getOrDefault(1)
    }
}
