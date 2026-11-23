package com.example.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.PrinterEntity
import com.example.printer.DiscoveredPrinter
import com.example.ui.components.NetworkGuardBanner
import com.example.ui.theme.LocalInkColors
import com.example.ui.theme.MonoFontFamily
import com.example.viewmodel.InkFlowUiState

@Composable
fun PrintersScreen(
    uiState: InkFlowUiState,
    onRefreshNetwork: () -> Unit,
    onToggleMdnsScan: () -> Unit,
    onSaveDiscovered: (DiscoveredPrinter) -> Unit,
    onAddManualPrinter: (name: String, host: String, port: Int, ippPath: String) -> Unit,
    onSelectPrinter: (Long) -> Unit,
    onDeletePrinter: (Long) -> Unit,
    onToggleForceWifiBinding: (Boolean) -> Unit,
    onToggleAutoResume: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Printers & Network Guard",
                    style = MaterialTheme.typography.headlineLarge
                )
                Text(
                    text = "Discover via mDNS, bind Wi-Fi sockets, or connect via HP Wi-Fi Direct",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            NetworkGuardBanner(
                networkState = uiState.networkGuard,
                pausedCount = uiState.pausedOrBlockedCount,
                forceWifiBinding = uiState.preferences.forceWifiSocketBinding,
                onRefreshNetwork = onRefreshNetwork
            )
        }

        // Discovery & Quick-Add Action Bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onToggleMdnsScan,
                    shape = CircleShape,
                    modifier = Modifier
                        .weight(1.2f)
                        .height(50.dp)
                        .testTag("mdns_scan_button")
                ) {
                    if (uiState.isScanningMdns) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Stop Scan")
                    } else {
                        Icon(Icons.Rounded.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Scan LAN (mDNS)")
                    }
                }

                FilledTonalButton(
                    onClick = { showAddDialog = true },
                    shape = CircleShape,
                    modifier = Modifier
                        .weight(0.9f)
                        .height(50.dp)
                        .testTag("add_manual_printer_button")
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add IP")
                }
            }
        }

        // 1-Tap Wi-Fi Direct Preset for HP Ink Tank 410 / 419
        item {
            OutlinedButton(
                onClick = {
                    onAddManualPrinter(
                        "HP Ink Tank 410/419 (Wi-Fi Direct)",
                        "192.168.223.1",
                        631,
                        "/ipp/print"
                    )
                },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("preset_wifi_direct_button")
            ) {
                Icon(Icons.Rounded.WifiTethering, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Quick-Add HP Wi-Fi Direct (192.168.223.1)")
            }
        }

        // Live Discovered mDNS Printers
        if (uiState.discoveredPrinters.isNotEmpty()) {
            item {
                Text(
                    text = "Discovered on Local Wi-Fi",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            items(uiState.discoveredPrinters, key = { "${it.host}:${it.port}" }) { found ->
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Router,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = found.serviceName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "${found.host}:${found.port}${found.ippPath}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = MonoFontFamily
                            )
                        }
                        Button(
                            onClick = { onSaveDiscovered(found) },
                            shape = CircleShape
                        ) {
                            Text("Use")
                        }
                    }
                }
            }
        }

        // Saved Printers List
        item {
            Text(
                text = "Saved Printers",
                style = MaterialTheme.typography.titleLarge
            )
        }

        items(uiState.printers, key = { it.id }) { printer ->
            SavedPrinterCard(
                printer = printer,
                canDelete = uiState.printers.size > 1,
                onSelect = { onSelectPrinter(printer.id) },
                onDelete = { onDeletePrinter(printer.id) }
            )
        }

        // Network Fallback Guard Settings
        item {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Wi-Fi / Mobile Data Guard",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Force Wi-Fi Socket Binding",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Locks IPP/LEDM sockets to Wi-Fi even if Mobile Data is ON or HP Wi-Fi Direct has no internet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.preferences.forceWifiSocketBinding,
                            onCheckedChange = onToggleForceWifiBinding,
                            modifier = Modifier.testTag("switch_force_wifi_binding")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Auto-Resume on Wi-Fi Return",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "If you accidentally switch to Mobile Data, paused jobs automatically restart once Wi-Fi reconnects.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.preferences.autoResumeOnWifiReturn,
                            onCheckedChange = onToggleAutoResume,
                            modifier = Modifier.testTag("switch_auto_resume")
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddPrinterDialog(
            suggestedSubnet = uiState.networkGuard.wifiLocalIp?.substringBeforeLast(".")?.let { "$it." } ?: "192.168.1.",
            onDismiss = { showAddDialog = false },
            onSave = { name, host, port, path ->
                onAddManualPrinter(name, host, port, path)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun SavedPrinterCard(
    printer: PrinterEntity,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val ink = LocalInkColors.current
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (printer.isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(
            width = if (printer.isSelected) 1.5.dp else 1.dp,
            color = if (printer.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onSelect)
            .testTag("printer_card_${printer.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        if (printer.isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (printer.isWiFiDirect) Icons.Rounded.WifiTethering else Icons.Rounded.Print,
                    contentDescription = null,
                    tint = if (printer.isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = printer.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "${printer.host}:${printer.port}${printer.ippPath}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = printer.modelHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (printer.isSelected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Active printer",
                    tint = ink.online
                )
            } else if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove printer")
                }
            }
        }
    }
}

@Composable
private fun AddPrinterDialog(
    suggestedSubnet: String,
    onDismiss: () -> Unit,
    onSave: (String, String, Int, String) -> Unit
) {
    var name by remember { mutableStateOf("HP Ink Tank 419 (Home Wi-Fi)") }
    var host by remember { mutableStateOf(suggestedSubnet) }
    var port by remember { mutableStateOf("631") }
    var path by remember { mutableStateOf("/ipp/print") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Printer by IP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Printer Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("IP Address (e.g. 192.168.1.45)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_ip_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(0.4f)
                    )
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("IPP Path") },
                        singleLine = true,
                        modifier = Modifier.weight(0.6f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, host, port.toIntOrNull() ?: 631, path) },
                modifier = Modifier.testTag("save_manual_printer_button")
            ) {
                Text("Save & Probe")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
