package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.printer.HardwareErrorGuide
import com.example.printer.HpLedmClient
import com.example.ui.components.InkTankBottleColumn
import com.example.ui.theme.LocalInkColors
import com.example.ui.theme.MonoFontFamily
import com.example.viewmodel.InkFlowUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    uiState: InkFlowUiState,
    onProbeNow: () -> Unit,
    onPrintTestPage: () -> Unit,
    onPreviewTestPage: () -> Unit,
    onCleanPrinthead: (Int) -> Unit,
    onAlignPrinthead: () -> Unit,
    onHardwareResume: () -> Unit,
    onSaveVisualInk: (Int, Int, Int, Int) -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ink = LocalInkColors.current
    val telem = uiState.telemetry
    val printer = uiState.selectedPrinter
    val prefs = uiState.preferences

    var showInkCalibrator by remember { mutableStateOf(false) }
    var expandedGuideCode by remember { mutableStateOf<String?>(null) }

    // Resolve live LEDM percentages if the printer firmware reports them; otherwise use user's visual tank calibration
    val ledmC = telem.consumables.firstOrNull { it.colorCode.contains("C", true) }?.percentageRemaining
    val ledmM = telem.consumables.firstOrNull { it.colorCode.contains("M", true) }?.percentageRemaining
    val ledmY = telem.consumables.firstOrNull { it.colorCode.contains("Y", true) }?.percentageRemaining
    val ledmK = telem.consumables.firstOrNull { it.colorCode.contains("K", true) || it.colorCode.contains("BK", true) }?.percentageRemaining

    val cPct = ledmC ?: prefs.visualInkCyan
    val mPct = ledmM ?: prefs.visualInkMagenta
    val yPct = ledmY ?: prefs.visualInkYellow
    val kPct = ledmK ?: prefs.visualInkBlack
    val usingVisualCalibration = ledmC == null && ledmM == null && ledmY == null && ledmK == null

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header + Live Probe Trigger
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "410 / 419 Diagnostics",
                        style = MaterialTheme.typography.headlineLarge
                    )
                    Text(
                        text = "Live IPP 2.0 & HP LEDM XML hardware lab",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = onProbeNow,
                    enabled = !uiState.isProbingPrinter,
                    shape = CircleShape,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("diag_probe_button")
                ) {
                    if (uiState.isProbingPrinter) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Rounded.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Probe")
                }
            }
        }

        // 1. Live Hardware Telemetry Summary Card
        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = telem.modelName.ifBlank { printer?.modelHint ?: "HP Ink Tank 410 / 419" },
                            style = MaterialTheme.typography.titleLarge
                        )
                        Surface(
                            shape = CircleShape,
                            color = if (telem.reachable) ink.online.copy(alpha = 0.18f) else ink.paused.copy(alpha = 0.18f)
                        ) {
                            Text(
                                text = if (telem.reachable) "${telem.latencyMs} ms" else "UNPROBED",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = MonoFontFamily,
                                color = if (telem.reachable) ink.online else ink.paused,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    TelemetryRow("Endpoint", printer?.ippUri ?: "Not configured")
                    TelemetryRow("LEDM Status", telem.ledmStatusCategory)
                    TelemetryRow("IPP State", "${telem.ippStateLabel} (Queue: ${telem.queuedJobsOnPrinter})")
                    if (telem.firmwareVersion.isNotBlank()) {
                        TelemetryRow("Firmware", telem.firmwareVersion)
                    }
                    if (telem.serialNumber.isNotBlank()) {
                        TelemetryRow("Serial", telem.serialNumber)
                    }
                    if (telem.totalPagesPrinted != null) {
                        TelemetryRow(
                            "Lifetime Pages",
                            "${telem.totalPagesPrinted} total • ${telem.colorPagesPrinted ?: 0} color • ${telem.jamEventsCount ?: 0} jams"
                        )
                    }
                    val formats = telem.supportedFormats.ifEmpty { printer?.supportedFormats.orEmpty() }
                    if (formats.isNotEmpty()) {
                        TelemetryRow("IPP Formats", formats.joinToString(", "))
                    }

                    if (!telem.errorMessage.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = telem.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. CMYK Side Ink Tanks Visualizer (Honest LEDM + Visual Calibration for 410/419 Transparent Tanks)
        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "CMYK Ink Tank Levels",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = if (usingVisualCalibration) {
                                    "HP 410/419 uses visual transparent tanks — calibrate below"
                                } else {
                                    "Reported via LEDM ConsumableConfigDyn.xml"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { showInkCalibrator = true },
                            modifier = Modifier.testTag("calibrate_ink_button")
                        ) {
                            Icon(Icons.Rounded.Tune, contentDescription = "Calibrate visual ink tank levels")
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        InkTankBottleColumn("C", "Cyan", cPct, ink.cyan, usingVisualCalibration)
                        InkTankBottleColumn("M", "Magenta", mPct, ink.magenta, usingVisualCalibration)
                        InkTankBottleColumn("Y", "Yellow", yPct, ink.yellow, usingVisualCalibration)
                        InkTankBottleColumn("K", "Black", kPct, ink.black, usingVisualCalibration)
                    }
                }
            }
        }

        // 3. Print Quality Diagnostic Sheet & LEDM Maintenance Controls
        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Printhead & Quality Maintenance",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onPrintTestPage,
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1.3f)
                                .height(50.dp)
                                .testTag("diag_print_nozzle_button")
                        ) {
                            Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Print Nozzle Sheet")
                        }
                        FilledTonalButton(
                            onClick = onPreviewTestPage,
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(0.9f)
                                .height(50.dp)
                                .testTag("diag_preview_sheet_button")
                        ) {
                            Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Preview")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { onCleanPrinthead(1) },
                            enabled = !uiState.isRunningMaintenance,
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("diag_clean_l1_button")
                        ) {
                            Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Clean L1")
                        }
                        FilledTonalButton(
                            onClick = { onCleanPrinthead(2) },
                            enabled = !uiState.isRunningMaintenance,
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("diag_clean_l2_button")
                        ) {
                            Text("Deep Clean L2")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onAlignPrinthead,
                            enabled = !uiState.isRunningMaintenance,
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("diag_align_button")
                        ) {
                            Icon(Icons.Rounded.GridOn, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Align Heads")
                        }
                        OutlinedButton(
                            onClick = onHardwareResume,
                            enabled = !uiState.isRunningMaintenance,
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("diag_resume_hw_button")
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Unblock HW")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            val url = printer?.ewsBaseUrl ?: "http://192.168.223.1"
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        },
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("diag_open_ews_button")
                    ) {
                        Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open Printer EWS Console (${printer?.host ?: "192.168.223.1"})")
                    }
                }
            }
        }

        // 4. HP Ink Tank 410 / 419 Error Code & Hardware Troubleshooter
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Ink Tank 410 / 419 Error Decoder",
                    style = MaterialTheme.typography.headlineSmall
                )
                HpLedmClient.INK_TANK_410_419_GUIDES.forEach { guide ->
                    HardwareGuideAccordion(
                        guide = guide,
                        expanded = expandedGuideCode == guide.code,
                        onToggle = {
                            expandedGuideCode = if (expandedGuideCode == guide.code) null else guide.code
                        }
                    )
                }
            }
        }

        // 5. Live Monospace IPP / LEDM Protocol Console
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF0E131D),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "LIVE IPP / LEDM CONSOLE",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = MonoFontFamily,
                            color = Color(0xFF6DD5FA)
                        )
                        IconButton(onClick = onClearLogs) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = "Clear protocol logs",
                                tint = Color(0xFF94A3B8)
                            )
                        }
                    }

                    if (uiState.logs.isEmpty()) {
                        Text(
                            text = "> Ready. Tap Probe or print a file to stream real IPP/LEDM packets.",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = MonoFontFamily,
                            color = Color(0xFF94A3B8)
                        )
                    } else {
                        val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            uiState.logs.take(18).forEach { log ->
                                Text(
                                    text = "${fmt.format(Date(log.timestampMs))} [${log.tag}] ${log.message}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = MonoFontFamily,
                                    color = if (log.isError) Color(0xFFFCA5A5) else Color(0xFFE2E8F0)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showInkCalibrator) {
        InkCalibrationDialog(
            initialC = prefs.visualInkCyan,
            initialM = prefs.visualInkMagenta,
            initialY = prefs.visualInkYellow,
            initialK = prefs.visualInkBlack,
            onDismiss = { showInkCalibrator = false },
            onSave = { c, m, y, k ->
                onSaveVisualInk(c, m, y, k)
                showInkCalibrator = false
            }
        )
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(108.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MonoFontFamily,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HardwareGuideAccordion(
    guide: HardwareErrorGuide,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onToggle)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = guide.code,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = MonoFontFamily,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = guide.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = guide.symptom,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse guide" else "Expand guide"
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(2.dp))
                    guide.steps.forEachIndexed { idx, step ->
                        Text(
                            text = "${idx + 1}. $step",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InkCalibrationDialog(
    initialC: Int,
    initialM: Int,
    initialY: Int,
    initialK: Int,
    onDismiss: () -> Unit,
    onSave: (Int, Int, Int, Int) -> Unit
) {
    var c by remember { mutableIntStateOf(initialC) }
    var m by remember { mutableIntStateOf(initialM) }
    var y by remember { mutableIntStateOf(initialY) }
    var k by remember { mutableIntStateOf(initialK) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibrate Visual Ink Tanks") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "HP Ink Tank 410/419 tanks are visually inspected. Match these sliders to your physical side bottles:",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Cyan: $c%", fontWeight = FontWeight.Bold)
                Slider(value = c.toFloat(), onValueChange = { c = it.toInt() }, valueRange = 0f..100f)
                Text("Magenta: $m%", fontWeight = FontWeight.Bold)
                Slider(value = m.toFloat(), onValueChange = { m = it.toInt() }, valueRange = 0f..100f)
                Text("Yellow: $y%", fontWeight = FontWeight.Bold)
                Slider(value = y.toFloat(), onValueChange = { y = it.toInt() }, valueRange = 0f..100f)
                Text("Black: $k%", fontWeight = FontWeight.Bold)
                Slider(value = k.toFloat(), onValueChange = { k = it.toInt() }, valueRange = 0f..100f)
            }
        },
        confirmButton = {
            Button(onClick = { onSave(c, m, y, k) }) { Text("Save Levels") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
