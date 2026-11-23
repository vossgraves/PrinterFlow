package com.example.printer

import com.example.data.local.PaperSize
import com.example.data.local.PrintColorMode
import com.example.data.local.PrintQuality
import com.example.data.local.PrinterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

data class IppPrinterAttributes(
    val statusCode: Int,
    val statusName: String,
    val printerName: String = "",
    val makeAndModel: String = "",
    val printerState: Int = 3, // 3=idle, 4=processing, 5=stopped
    val printerStateReasons: List<String> = emptyList(),
    val supportedFormats: List<String> = emptyList(),
    val queuedJobCount: Int = 0,
    val rawAttributes: Map<String, List<String>> = emptyMap()
) {
    val printerStateLabel: String
        get() = when (printerState) {
            3 -> "Idle & Ready"
            4 -> "Processing / Printing"
            5 -> "Stopped / Blocked"
            else -> "State ($printerState)"
        }

    val isSuccessful: Boolean
        get() = statusCode in 0x0000..0x00FF
}

data class IppOperationResult(
    val success: Boolean,
    val statusCode: Int,
    val statusName: String,
    val jobId: Int = -1,
    val message: String = ""
)

/**
 * RFC 8010 / RFC 8011 IPP 2.0 binary protocol encoder & decoder for HP Ink Tank 410/419.
 */
class IppProtocolEngine {

    private val requestIdCounter = AtomicInteger(100)

    companion object {
        private const val IPP_MAJOR = 2
        private const val IPP_MINOR = 0

        // Operations
        const val OP_PRINT_JOB = 0x0002
        const val OP_VALIDATE_JOB = 0x0004
        const val OP_CANCEL_JOB = 0x0008
        const val OP_GET_JOBS = 0x000A
        const val OP_GET_PRINTER_ATTRIBUTES = 0x000B
        const val OP_HOLD_JOB = 0x000C
        const val OP_RELEASE_JOB = 0x000D
        const val OP_PAUSE_PRINTER = 0x0010
        const val OP_RESUME_PRINTER = 0x0011

        // Delimiter tags
        private const val TAG_OPERATION_ATTRIBUTES = 0x01
        private const val TAG_JOB_ATTRIBUTES = 0x02
        private const val TAG_END_OF_ATTRIBUTES = 0x03

        // Value tags
        private const val TAG_INTEGER = 0x21
        private const val TAG_BOOLEAN = 0x22
        private const val TAG_ENUM = 0x23
        private const val TAG_TEXT_WITHOUT_LANG = 0x41
        private const val TAG_NAME_WITHOUT_LANG = 0x42
        private const val TAG_KEYWORD = 0x44
        private const val TAG_URI = 0x45
        private const val TAG_CHARSET = 0x47
        private const val TAG_NATURAL_LANG = 0x48
        private const val TAG_MIME_MEDIA_TYPE = 0x49

        private val IPP_MEDIA_TYPE = "application/ipp".toMediaType()

        fun describeStatusCode(code: Int): String = when (code) {
            0x0000 -> "successful-ok"
            0x0001 -> "successful-ok-ignored-or-substituted-attributes"
            0x0002 -> "successful-ok-conflicting-attributes"
            0x0400 -> "client-error-bad-request"
            0x0401 -> "client-error-forbidden"
            0x0404 -> "client-error-not-found"
            0x0406 -> "client-error-not-found (job/resource)"
            0x040A -> "client-error-document-format-not-supported"
            0x040B -> "client-error-attributes-or-values-not-supported"
            0x040E -> "client-error-charset-not-supported"
            0x0500 -> "server-error-internal-error"
            0x0501 -> "server-error-operation-not-supported"
            0x0505 -> "server-error-version-not-supported"
            0x0506 -> "server-error-device-error"
            0x0507 -> "server-error-printer-busy"
            else -> "ipp-status-0x${code.toString(16).padStart(4, '0')}"
        }
    }

    suspend fun getPrinterAttributes(
        client: OkHttpClient,
        printer: PrinterEntity
    ): Result<IppPrinterAttributes> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildPacket(OP_GET_PRINTER_ATTRIBUTES) {
                writeStringAttr(TAG_CHARSET, "attributes-charset", "utf-8")
                writeStringAttr(TAG_NATURAL_LANG, "attributes-natural-language", "en")
                writeStringAttr(TAG_URI, "printer-uri", printer.ippUri)
                writeStringAttr(TAG_KEYWORD, "requested-attributes", "printer-name")
                writeStringAttr(TAG_KEYWORD, "", "printer-make-and-model")
                writeStringAttr(TAG_KEYWORD, "", "printer-state")
                writeStringAttr(TAG_KEYWORD, "", "printer-state-reasons")
                writeStringAttr(TAG_KEYWORD, "", "document-format-supported")
                writeStringAttr(TAG_KEYWORD, "", "queued-job-count")
            }

            val responseBytes = executeIppPost(client, printer.ippHttpUrl, payload)
            val parsed = parseResponse(responseBytes)

            val state = parsed.attributes["printer-state"]?.firstOrNull()?.toIntOrNull() ?: 3
            val queued = parsed.attributes["queued-job-count"]?.firstOrNull()?.toIntOrNull() ?: 0

            IppPrinterAttributes(
                statusCode = parsed.statusCode,
                statusName = describeStatusCode(parsed.statusCode),
                printerName = parsed.attributes["printer-name"]?.firstOrNull().orEmpty(),
                makeAndModel = parsed.attributes["printer-make-and-model"]?.firstOrNull().orEmpty(),
                printerState = state,
                printerStateReasons = parsed.attributes["printer-state-reasons"].orEmpty(),
                supportedFormats = parsed.attributes["document-format-supported"].orEmpty(),
                queuedJobCount = queued,
                rawAttributes = parsed.attributes
            )
        }
    }

    suspend fun printJob(
        client: OkHttpClient,
        printer: PrinterEntity,
        jobName: String,
        documentMimeType: String,
        documentBytes: ByteArray,
        copies: Int,
        colorMode: PrintColorMode,
        quality: PrintQuality,
        paperSize: PaperSize
    ): Result<IppOperationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val header = buildPacket(OP_PRINT_JOB, includeJobGroup = true) {
                writeStringAttr(TAG_CHARSET, "attributes-charset", "utf-8")
                writeStringAttr(TAG_NATURAL_LANG, "attributes-natural-language", "en")
                writeStringAttr(TAG_URI, "printer-uri", printer.ippUri)
                writeStringAttr(TAG_NAME_WITHOUT_LANG, "requesting-user-name", "InkFlow")
                writeStringAttr(TAG_NAME_WITHOUT_LANG, "job-name", jobName.take(127))
                writeStringAttr(TAG_MIME_MEDIA_TYPE, "document-format", documentMimeType)

                // Job template attributes
                writeByte(TAG_JOB_ATTRIBUTES)
                writeIntAttr(TAG_INTEGER, "copies", copies.coerceIn(1, 99))
                writeStringAttr(TAG_KEYWORD, "print-color-mode", colorMode.ippValue)
                writeIntAttr(TAG_ENUM, "print-quality", quality.ippCode)
                writeStringAttr(TAG_KEYWORD, "media", paperSize.ippMedia)
            }

            val fullBody = ByteArray(header.size + documentBytes.size)
            System.arraycopy(header, 0, fullBody, 0, header.size)
            System.arraycopy(documentBytes, 0, fullBody, header.size, documentBytes.size)

            val responseBytes = executeIppPost(client, printer.ippHttpUrl, fullBody)
            val parsed = parseResponse(responseBytes)
            val jobId = parsed.attributes["job-id"]?.firstOrNull()?.toIntOrNull() ?: -1
            val ok = parsed.statusCode in 0x0000..0x00FF
            val statusMsg = parsed.attributes["status-message"]?.firstOrNull()
                ?: describeStatusCode(parsed.statusCode)

            IppOperationResult(
                success = ok,
                statusCode = parsed.statusCode,
                statusName = describeStatusCode(parsed.statusCode),
                jobId = jobId,
                message = statusMsg
            )
        }
    }

    suspend fun sendJobControl(
        client: OkHttpClient,
        printer: PrinterEntity,
        operationId: Int,
        jobId: Int
    ): Result<IppOperationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildPacket(operationId) {
                writeStringAttr(TAG_CHARSET, "attributes-charset", "utf-8")
                writeStringAttr(TAG_NATURAL_LANG, "attributes-natural-language", "en")
                writeStringAttr(TAG_URI, "printer-uri", printer.ippUri)
                writeIntAttr(TAG_INTEGER, "job-id", jobId)
                writeStringAttr(TAG_NAME_WITHOUT_LANG, "requesting-user-name", "InkFlow")
            }
            val responseBytes = executeIppPost(client, printer.ippHttpUrl, payload)
            val parsed = parseResponse(responseBytes)
            IppOperationResult(
                success = parsed.statusCode in 0x0000..0x00FF,
                statusCode = parsed.statusCode,
                statusName = describeStatusCode(parsed.statusCode),
                jobId = jobId,
                message = parsed.attributes["status-message"]?.firstOrNull()
                    ?: describeStatusCode(parsed.statusCode)
            )
        }
    }

    suspend fun sendPrinterControl(
        client: OkHttpClient,
        printer: PrinterEntity,
        operationId: Int
    ): Result<IppOperationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildPacket(operationId) {
                writeStringAttr(TAG_CHARSET, "attributes-charset", "utf-8")
                writeStringAttr(TAG_NATURAL_LANG, "attributes-natural-language", "en")
                writeStringAttr(TAG_URI, "printer-uri", printer.ippUri)
                writeStringAttr(TAG_NAME_WITHOUT_LANG, "requesting-user-name", "InkFlow")
            }
            val responseBytes = executeIppPost(client, printer.ippHttpUrl, payload)
            val parsed = parseResponse(responseBytes)
            IppOperationResult(
                success = parsed.statusCode in 0x0000..0x00FF,
                statusCode = parsed.statusCode,
                statusName = describeStatusCode(parsed.statusCode),
                message = parsed.attributes["status-message"]?.firstOrNull()
                    ?: describeStatusCode(parsed.statusCode)
            )
        }
    }

    private fun executeIppPost(client: OkHttpClient, url: String, bodyBytes: ByteArray): ByteArray {
        val request = Request.Builder()
            .url(url)
            .post(bodyBytes.toRequestBody(IPP_MEDIA_TYPE))
            .header("Accept", "application/ipp")
            .header("User-Agent", "InkFlow-IPP/2.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} ${response.message} from $url")
            }
            return response.body?.bytes()
                ?: throw IllegalStateException("Empty IPP response body from $url")
        }
    }

    private fun buildPacket(
        operationId: Int,
        includeJobGroup: Boolean = false,
        block: DataOutputStream.() -> Unit
    ): ByteArray {
        val baos = ByteArrayOutputStream(512)
        val dos = DataOutputStream(baos)
        dos.writeByte(IPP_MAJOR)
        dos.writeByte(IPP_MINOR)
        dos.writeShort(operationId)
        dos.writeInt(requestIdCounter.getAndIncrement())
        dos.writeByte(TAG_OPERATION_ATTRIBUTES)
        dos.block()
        dos.writeByte(TAG_END_OF_ATTRIBUTES)
        dos.flush()
        return baos.toByteArray()
    }

    private fun DataOutputStream.writeStringAttr(tag: Int, name: String, value: String) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val valueBytes = value.toByteArray(StandardCharsets.UTF_8)
        writeByte(tag)
        writeShort(nameBytes.size)
        if (nameBytes.isNotEmpty()) write(nameBytes)
        writeShort(valueBytes.size)
        if (valueBytes.isNotEmpty()) write(valueBytes)
    }

    private fun DataOutputStream.writeIntAttr(tag: Int, name: String, value: Int) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        writeByte(tag)
        writeShort(nameBytes.size)
        if (nameBytes.isNotEmpty()) write(nameBytes)
        writeShort(4)
        writeInt(value)
    }

    private data class ParsedIppResponse(
        val versionMajor: Int,
        val versionMinor: Int,
        val statusCode: Int,
        val requestId: Int,
        val attributes: Map<String, List<String>>
    )

    private fun parseResponse(bytes: ByteArray): ParsedIppResponse {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val major = dis.readUnsignedByte()
        val minor = dis.readUnsignedByte()
        val statusCode = dis.readUnsignedShort()
        val reqId = dis.readInt()

        val attrs = LinkedHashMap<String, MutableList<String>>()
        var lastAttrName = ""

        while (dis.available() > 0) {
            val tag = dis.readUnsignedByte()
            if (tag == TAG_END_OF_ATTRIBUTES) break
            // Delimiter tags (0x00..0x0F) start a new attribute group
            if (tag <= 0x0F) {
                lastAttrName = ""
                continue
            }
            if (dis.available() < 2) break
            val nameLen = dis.readUnsignedShort()
            val name = if (nameLen > 0 && dis.available() >= nameLen) {
                val buf = ByteArray(nameLen)
                dis.readFully(buf)
                String(buf, StandardCharsets.UTF_8).also { lastAttrName = it }
            } else {
                lastAttrName
            }
            if (dis.available() < 2) break
            val valLen = dis.readUnsignedShort()
            if (dis.available() < valLen) break
            val valBytes = ByteArray(valLen)
            if (valLen > 0) dis.readFully(valBytes)

            val decoded = when (tag) {
                TAG_INTEGER, TAG_ENUM -> {
                    if (valLen == 4) {
                        ((valBytes[0].toInt() and 0xFF) shl 24 or
                            ((valBytes[1].toInt() and 0xFF) shl 16) or
                            ((valBytes[2].toInt() and 0xFF) shl 8) or
                            (valBytes[3].toInt() and 0xFF)).toString()
                    } else ""
                }
                TAG_BOOLEAN -> if (valLen >= 1 && valBytes[0].toInt() != 0) "true" else "false"
                else -> String(valBytes, StandardCharsets.UTF_8)
            }
            if (name.isNotEmpty() && decoded.isNotEmpty()) {
                attrs.getOrPut(name) { mutableListOf() }.add(decoded)
            }
        }

        return ParsedIppResponse(major, minor, statusCode, reqId, attrs)
    }
}
