package com.example.printer

import android.util.Xml
import com.example.data.local.PrinterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.InetSocketAddress
import javax.net.SocketFactory

data class LedmConsumable(
    val label: String,
    val colorCode: String,
    val percentageRemaining: Int?, // null on optical/visual Ink Tank 410/419 if firmware omits %
    val state: String
)

data class PrinterTelemetry(
    val reachable: Boolean = false,
    val latencyMs: Long? = null,
    val checkedAtMs: Long = 0L,
    val modelName: String = "",
    val firmwareVersion: String = "",
    val serialNumber: String = "",
    val ledmStatusCategory: String = "Not probed yet",
    val ledmAlertCodes: List<String> = emptyList(),
    val ippStateLabel: String = "Unknown",
    val ippStateReasons: List<String> = emptyList(),
    val supportedFormats: List<String> = emptyList(),
    val queuedJobsOnPrinter: Int = 0,
    val totalPagesPrinted: Int? = null,
    val colorPagesPrinted: Int? = null,
    val jamEventsCount: Int? = null,
    val consumables: List<LedmConsumable> = emptyList(),
    val errorMessage: String? = null
)

data class MaintenanceResult(
    val success: Boolean,
    val message: String
)

data class HardwareErrorGuide(
    val code: String,
    val title: String,
    val symptom: String,
    val steps: List<String>
)

/**
 * Real HP LEDM (Lightweight Device Management) XML client & diagnostics suite for HP Ink Tank 410/419.
 */
class HpLedmClient(private val ippEngine: IppProtocolEngine) {

    companion object {
        val INK_TANK_410_419_GUIDES: List<HardwareErrorGuide> = listOf(
            HardwareErrorGuide(
                code = "E3",
                title = "Print Carriage Stall",
                symptom = "Blink E3 + Resume LED flashing; carriage cannot move freely.",
                steps = listOf(
                    "Open the exterior & printhead access doors and wait until carriage stops.",
                    "Remove any paper scraps or packing tape behind the carriage rail.",
                    "Confirm the blue printhead latch is snapped flat and ink valve is UNLOCKED (vertical).",
                    "Press the physical Resume button on the 410/419, then tap 'Unblock & Retry' in InkFlow."
                )
            ),
            HardwareErrorGuide(
                code = "E4",
                title = "Paper Jam / Rear Feed Block",
                symptom = "Blink E4; paper stuck in input tray, output tray, or bottom cleanout door.",
                steps = listOf(
                    "Pull paper gently with both hands from the output tray to prevent tearing.",
                    "Check the top input tray rollers for curled A4 sheets.",
                    "Load fresh dry paper snugly against the right guide and press Resume."
                )
            ),
            HardwareErrorGuide(
                code = "E0 / E8",
                title = "Printhead / Ink System Fault",
                symptom = "Tri-color or Black printhead icon blinking on LCD.",
                steps = listOf(
                    "Turn ink tank transport valve to LOCKED before opening printhead latch (prevents air backflow).",
                    "Reseat both Black & Tri-Color printheads and wipe gold copper contacts gently.",
                    "Close latch, return ink valve to UNLOCKED, and run 'Print Quality Diagnostic Page'."
                )
            ),
            HardwareErrorGuide(
                code = "AIR",
                title = "Air Bubbles in Ink Tubes / Faint Streaks",
                symptom = "Blank pages or missing Cyan/Magenta/Yellow stripes after sitting idle.",
                steps = listOf(
                    "Verify all 4 side ink tanks are above the minimum fill line.",
                    "Run Level 1 Printhead Cleaning from Diagnostics below, then print a Nozzle Check.",
                    "If gaps remain in color bars, wait 10 minutes and run Level 2 Deep Clean once."
                )
            ),
            HardwareErrorGuide(
                code = "WIFI",
                title = "Wi-Fi Direct / Mobile Data Conflict",
                symptom = "Printer says Offline in HP app whenever mobile data is enabled.",
                steps = listOf(
                    "Keep 'Force Wi-Fi Socket Binding' ON in InkFlow Printers tab.",
                    "For Wi-Fi Direct, hold the Wi-Fi Direct button on the 410/419 until the icon appears, connect phone Wi-Fi to DIRECT-xx-HP Ink Tank 410 (default PIN 12345678).",
                    "Select the 192.168.223.1 preset in InkFlow — it routes straight over Wi-Fi even with Cellular ON."
                )
            )
        )
    }

    suspend fun probePrinterFull(
        client: OkHttpClient,
        socketFactory: SocketFactory,
        printer: PrinterEntity
    ): PrinterTelemetry = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val latency = measureTcpLatency(socketFactory, printer.host, printer.port)
            ?: measureTcpLatency(socketFactory, printer.host, 80)

        if (latency == null) {
            return@withContext PrinterTelemetry(
                reachable = false,
                latencyMs = null,
                checkedAtMs = now,
                ledmStatusCategory = "Unreachable",
                ippStateLabel = "Offline",
                errorMessage = "TCP timeout reaching ${printer.host}:${printer.port} & :80. Verify Wi-Fi connection or printer power."
            )
        }

        // 1. Query IPP 2.0 Get-Printer-Attributes
        val ippAttrs = ippEngine.getPrinterAttributes(client, printer).getOrNull()

        // 2. Query HP LEDM XML endpoints on port 80
        val statusXml = fetchXml(client, "${printer.ewsBaseUrl}/DevMgmt/ProductStatusDyn.xml")
        val configXml = fetchXml(client, "${printer.ewsBaseUrl}/DevMgmt/ProductConfigDyn.xml")
        val usageXml = fetchXml(client, "${printer.ewsBaseUrl}/DevMgmt/ProductUsageDyn.xml")
        val consumableXml = fetchXml(client, "${printer.ewsBaseUrl}/DevMgmt/ConsumableConfigDyn.xml")

        val statusParsed = parseStatusXml(statusXml)
        val configParsed = parseConfigXml(configXml)
        val usageParsed = parseUsageXml(usageXml)
        val consumables = parseConsumablesXml(consumableXml)

        val resolvedModel = configParsed.model.ifBlank {
            ippAttrs?.makeAndModel?.ifBlank { printer.modelHint } ?: printer.modelHint
        }

        PrinterTelemetry(
            reachable = true,
            latencyMs = latency,
            checkedAtMs = now,
            modelName = resolvedModel,
            firmwareVersion = configParsed.firmware,
            serialNumber = configParsed.serial,
            ledmStatusCategory = statusParsed.first.ifBlank {
                if (ippAttrs != null) ippAttrs.printerStateLabel else "Online (HTTP Ready)"
            },
            ledmAlertCodes = statusParsed.second,
            ippStateLabel = ippAttrs?.printerStateLabel ?: "IPP Not Responding",
            ippStateReasons = ippAttrs?.printerStateReasons.orEmpty(),
            supportedFormats = ippAttrs?.supportedFormats.orEmpty(),
            queuedJobsOnPrinter = ippAttrs?.queuedJobCount ?: 0,
            totalPagesPrinted = usageParsed.totalPages,
            colorPagesPrinted = usageParsed.colorPages,
            jamEventsCount = usageParsed.jamEvents,
            consumables = consumables,
            errorMessage = null
        )
    }

    suspend fun triggerPrintheadClean(
        client: OkHttpClient,
        printer: PrinterEntity,
        level: Int = 1
    ): MaintenanceResult = withContext(Dispatchers.IO) {
        val actionId = when (level) {
            2 -> "CleanPrintheadLevel2"
            else -> "CleanPrinthead"
        }
        val ledmXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <prdact:ProductActions xmlns:prdact="http://www.hp.com/schemas/imaging/con/ledm/productactions/2008/03/28"
                                   xmlns:dd="http://www.hp.com/schemas/imaging/con/dictionaries/1.0/">
                <prdact:Action>
                    <dd:ActionType>$actionId</dd:ActionType>
                </prdact:Action>
            </prdact:ProductActions>
        """.trimIndent()

        postMaintenanceCandidates(
            client = client,
            urls = listOf(
                "${printer.ewsBaseUrl}/DevMgmt/ProductActions",
                "${printer.ewsBaseUrl}/DevMgmt/InternalPrintDyn.xml"
            ),
            xmlBody = ledmXml,
            actionLabel = "Printhead Clean (Level $level)"
        )
    }

    suspend fun triggerPrintheadAlignment(
        client: OkHttpClient,
        printer: PrinterEntity
    ): MaintenanceResult = withContext(Dispatchers.IO) {
        val ledmXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <prdact:ProductActions xmlns:prdact="http://www.hp.com/schemas/imaging/con/ledm/productactions/2008/03/28"
                                   xmlns:dd="http://www.hp.com/schemas/imaging/con/dictionaries/1.0/">
                <prdact:Action>
                    <dd:ActionType>AlignPrinthead</dd:ActionType>
                </prdact:Action>
            </prdact:ProductActions>
        """.trimIndent()

        postMaintenanceCandidates(
            client = client,
            urls = listOf(
                "${printer.ewsBaseUrl}/DevMgmt/ProductActions",
                "${printer.ewsBaseUrl}/DevMgmt/InternalPrintDyn.xml"
            ),
            xmlBody = ledmXml,
            actionLabel = "Printhead Alignment"
        )
    }

    private fun postMaintenanceCandidates(
        client: OkHttpClient,
        urls: List<String>,
        xmlBody: String,
        actionLabel: String
    ): MaintenanceResult {
        var lastErr = ""
        val mediaType = "text/xml; charset=utf-8".toMediaType()
        for (url in urls) {
            val res = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .post(xmlBody.toRequestBody(mediaType))
                    .header("User-Agent", "InkFlow-LEDM/1.0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful || resp.code == 202) {
                        return MaintenanceResult(
                            true,
                            "$actionLabel triggered on printer via LEDM (HTTP ${resp.code})."
                        )
                    } else {
                        lastErr = "HTTP ${resp.code} at ${url.substringAfterLast('/')}"
                    }
                }
            }
            if (res.isFailure) {
                lastErr = res.exceptionOrNull()?.message ?: "Connection refused"
            }
        }
        return MaintenanceResult(
            success = false,
            message = "Firmware rejected direct LEDM $actionLabel ($lastErr). Use 'Open Printer EWS Console' -> Tools -> Print Quality Toolbox, or print InkFlow's Nozzle Diagnostic Page."
        )
    }

    private fun measureTcpLatency(
        socketFactory: SocketFactory,
        host: String,
        port: Int
    ): Long? {
        return runCatching {
            val start = System.nanoTime()
            socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2500)
            }
            ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
        }.getOrNull()
    }

    private fun fetchXml(client: OkHttpClient, url: String): String? {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/xml, text/xml")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    private fun parseStatusXml(xml: String?): Pair<String, List<String>> {
        if (xml.isNullOrBlank()) return "" to emptyList()
        val categories = mutableListOf<String>()
        val alerts = mutableListOf<String>()
        runCatching {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var currentTag = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> currentTag = parser.name.substringAfter(':')
                    XmlPullParser.TEXT -> {
                        val txt = parser.text?.trim().orEmpty()
                        if (txt.isNotEmpty()) {
                            when (currentTag) {
                                "StatusCategory" -> categories.add(txt)
                                "AlertID", "StringId" -> alerts.add(txt)
                            }
                        }
                    }
                }
                event = parser.next()
            }
        }
        val primary = categories.firstOrNull { !it.equals("ready", ignoreCase = true) }
            ?: categories.firstOrNull().orEmpty()
        return primary to (categories.drop(1) + alerts).distinct().take(6)
    }

    private data class ParsedConfig(val model: String = "", val firmware: String = "", val serial: String = "")

    private fun parseConfigXml(xml: String?): ParsedConfig {
        if (xml.isNullOrBlank()) return ParsedConfig()
        var model = ""
        var firmware = ""
        var serial = ""
        runCatching {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var currentTag = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> currentTag = parser.name.substringAfter(':')
                    XmlPullParser.TEXT -> {
                        val txt = parser.text?.trim().orEmpty()
                        if (txt.isNotEmpty()) {
                            when (currentTag) {
                                "MakeAndModel", "MakeAndModelBase" -> if (model.isBlank()) model = txt
                                "Revision" -> if (firmware.isBlank()) firmware = txt
                                "SerialNumber" -> if (serial.isBlank()) serial = txt
                            }
                        }
                    }
                }
                event = parser.next()
            }
        }
        return ParsedConfig(model, firmware, serial)
    }

    private data class ParsedUsage(
        val totalPages: Int? = null,
        val colorPages: Int? = null,
        val jamEvents: Int? = null
    )

    private fun parseUsageXml(xml: String?): ParsedUsage {
        if (xml.isNullOrBlank()) return ParsedUsage()
        var total: Int? = null
        var color: Int? = null
        var jams: Int? = null
        runCatching {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var currentTag = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> currentTag = parser.name.substringAfter(':')
                    XmlPullParser.TEXT -> {
                        val num = parser.text?.trim()?.toIntOrNull()
                        if (num != null) {
                            when (currentTag) {
                                "TotalImpressions" -> if (total == null) total = num
                                "ColorImpressions" -> if (color == null) color = num
                                "JamEvents" -> if (jams == null) jams = num
                            }
                        }
                    }
                }
                event = parser.next()
            }
        }
        return ParsedUsage(total, color, jams)
    }

    private fun parseConsumablesXml(xml: String?): List<LedmConsumable> {
        if (xml.isNullOrBlank()) return emptyList()
        val result = mutableListOf<LedmConsumable>()
        runCatching {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var inConsumable = false
            var currentTag = ""
            var label = ""
            var colorCode = ""
            var pct: Int? = null
            var state = "OK"

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name.substringAfter(':')
                        currentTag = name
                        if (name == "ConsumableInfo") {
                            inConsumable = true
                            label = ""
                            colorCode = ""
                            pct = null
                            state = "OK"
                        }
                    }
                    XmlPullParser.TEXT -> if (inConsumable) {
                        val txt = parser.text?.trim().orEmpty()
                        if (txt.isNotEmpty()) {
                            when (currentTag) {
                                "ConsumableLabelCode" -> colorCode = txt
                                "ConsumableStation" -> if (label.isBlank()) label = "Station $txt"
                                "ConsumablePercentageLevelRemaining" -> pct = txt.toIntOrNull()
                                "ConsumableState" -> state = txt
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.substringAfter(':') == "ConsumableInfo" && inConsumable) {
                            inConsumable = false
                            if (colorCode.isNotBlank() || label.isNotBlank()) {
                                result.add(
                                    LedmConsumable(
                                        label = label.ifBlank { colorCode },
                                        colorCode = colorCode.ifBlank { "K" },
                                        percentageRemaining = pct,
                                        state = state
                                    )
                                )
                            }
                        }
                    }
                }
                event = parser.next()
            }
        }
        return result
    }
}
