package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.BuildCircle
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.JobStatus
import com.example.data.local.PrintJobEntity
import com.example.ui.components.JobStatusPill
import com.example.ui.components.NetworkGuardBanner
import com.example.ui.theme.LocalInkColors
import com.example.ui.theme.MonoFontFamily
import com.example.viewmodel.InkFlowUiState

@Composable
fun QueueScreen(
    uiState: InkFlowUiState,
    onRefreshNetwork: () -> Unit,
    onProbePrinter: () -> Unit,
    onPauseJob: (Long) -> Unit,
    onResumeJob: (Long) -> Unit,
    onCancelJob: (Long) -> Unit,
    onDeleteJob: (Long) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onClearDone: () -> Unit,
    onNavigateToPrint: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToPrinters: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Live Network Guard Banner (Wi-Fi Socket Bound / Mobile Data Fallback)
        item(key = "net_guard") {
            NetworkGuardBanner(
                networkState = uiState.networkGuard,
                pausedCount = uiState.pausedOrBlockedCount,
                forceWifiBinding = uiState.preferences.forceWifiSocketBinding,
                onRefreshNetwork = onRefreshNetwork
            )
        }

        // 2. Expressive Ink Tank Hardware Hero Card
        item(key = "printer_hero") {
            PrinterHeroCard(
                uiState = uiState,
                onProbe = onProbePrinter,
                onOpenDiagnostics = onNavigateToDiagnostics,
                onOpenPrinters = onNavigateToPrinters
            )
        }

        // 3. Spooler Bulk Control Bar (Pause All / Resume All / Clear Done)
        item(key = "spooler_controls") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Print Spooler",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "${uiState.activeJobsCount} active • ${uiState.pausedOrBlockedCount} paused/blocked",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (uiState.jobs.any { it.jobStatus == JobStatus.COMPLETED || it.jobStatus == JobStatus.CANCELLED }) {
                    TextButton(
                        onClick = onClearDone,
                        modifier = Modifier.testTag("clear_done_button")
                    ) {
                        Text("Clear Done")
                    }
                }
            }

            if (uiState.jobs.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = onPauseAll,
                        enabled = uiState.activeJobsCount > 0,
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("pause_all_button")
                    ) {
                        Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pause All")
                    }
                    Button(
                        onClick = onResumeAll,
                        enabled = uiState.pausedOrBlockedCount > 0,
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("resume_all_button")
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Resume All")
                    }
                }
            }
        }

        // 4. Queue Items or Expressive Empty State
        if (uiState.jobs.isEmpty()) {
            item(key = "empty_queue") {
                EmptyQueueCard(
                    onPickDocument = onNavigateToPrint,
                    onOpenDiagnostics = onNavigateToDiagnostics
                )
            }
        } else {
            items(uiState.jobs, key = { it.id }) { job ->
                PrintJobCard(
                    job = job,
                    onPause = { onPauseJob(job.id) },
                    onResume = { onResumeJob(job.id) },
                    onCancel = { onCancelJob(job.id) },
                    onDelete = { onDeleteJob(job.id) }
                )
            }
        }
    }
}

@Composable
private fun PrinterHeroCard(
    uiState: InkFlowUiState,
    onProbe: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenPrinters: () -> Unit
) {
    val printer = uiState.selectedPrinter
    val ink = LocalInkColors.current
    val telem = uiState.telemetry

    Surface(
        shape = RoundedCornerShape(30.dp),
        color = Color(0xFF141B29),
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("printer_hero_card")
    ) {
        Box {
            Image(
                painter = painterResource(id = R.drawable.img_inktank_hero_1795112957592),
                contentDescription = "HP Ink Tank 410 and 419 printer visual",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(196.dp)
            )
            // Smooth expressive editorial scrim for crisp typography contrast
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xCC0D1320),
                                Color(0x730D1320),
                                Color(0xEB0D1320)
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (telem.reachable) ink.online.copy(alpha = 0.22f)
                        else Color.White.copy(alpha = 0.14f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (telem.reachable) ink.online else ink.paused)
                            )
                            Text(
                                text = when {
                                    uiState.isProbingPrinter -> "PROBING..."
                                    telem.reachable -> "ONLINE • ${telem.latencyMs}ms"
                                    else -> "TAP PROBE TO CHECK"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontFamily = MonoFontFamily
                            )
                        }
                    }

                    IconButton(
                        onClick = onProbe,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f))
                            .testTag("hero_probe_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Probe printer status",
                            tint = Color.White
                        )
                    }
                }

                Spacer(Modifier.height(34.dp))

                Text(
                    text = printer?.name ?: "No Printer Configured",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (printer != null) {
                        "${printer.host}:${printer.port}${printer.ippPath}  •  ${telem.ledmStatusCategory}"
                    } else {
                        "Tap Switch Printer to scan LAN or use Wi-Fi Direct"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.80f),
                    fontFamily = MonoFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onOpenDiagnostics,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF0E172A)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("hero_diagnostics_button")
                    ) {
                        Icon(Icons.Rounded.BuildCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("410/419 Lab")
                    }
                    OutlinedButton(
                        onClick = onOpenPrinters,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("hero_switch_printer_button")
                    ) {
                        Text("Switch Printer")
                    }
                }
            }
        }
    }
}

@Composable
private fun PrintJobCard(
    job: PrintJobEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val status = job.jobStatus
    val ink = LocalInkColors.current
    val isPdf = job.mimeType.contains("pdf", ignoreCase = true)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
            .testTag("job_card_${job.id}"),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (status == JobStatus.BLOCKED_PRINTER_ERROR) 1.5.dp else 1.dp,
            color = when (status) {
                JobStatus.BLOCKED_PRINTER_ERROR -> ink.blocked.copy(alpha = 0.65f)
                JobStatus.PAUSED_NO_WIFI, JobStatus.PAUSED_BY_USER -> ink.paused.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPdf) Icons.Rounded.Description else Icons.Rounded.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.documentName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${job.pageCount}p • ${job.copies}× • ${job.parsedColorMode.label} • ${job.parsedPaperSize.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                JobStatusPill(status = status)
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = job.statusDetail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (status == JobStatus.RASTERIZING || status == JobStatus.SENDING) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                )
            }

            AnimatedVisibility(visible = !job.blockReason.isNullOrBlank()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
                ) {
                    Text(
                        text = job.blockReason.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Primary tactile Queue Actions (Pause / Resume / Unblock & Retry / Cancel)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    status.isActiveOrPending -> {
                        FilledTonalButton(
                            onClick = onPause,
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("pause_job_${job.id}")
                        ) {
                            Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pause")
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            shape = CircleShape,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("cancel_job_${job.id}")
                        ) {
                            Icon(Icons.Rounded.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    }

                    status.isPausedOrBlocked -> {
                        Button(
                            onClick = onResume,
                            shape = CircleShape,
                            colors = if (status == JobStatus.BLOCKED_PRINTER_ERROR) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            } else {
                                ButtonDefaults.buttonColors()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("resume_job_${job.id}")
                        ) {
                            Icon(
                                imageVector = if (status == JobStatus.BLOCKED_PRINTER_ERROR) Icons.Rounded.RestartAlt else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (status == JobStatus.BLOCKED_PRINTER_ERROR) "Unblock & Retry" else "Resume Print")
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            shape = CircleShape,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("cancel_job_${job.id}")
                        ) {
                            Text("Cancel")
                        }
                    }

                    else -> {
                        FilledTonalButton(
                            onClick = onResume,
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("reprint_job_${job.id}")
                        ) {
                            Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Print Again")
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("delete_job_${job.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Delete job from history"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyQueueCard(
    onPickDocument: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Print,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Local Spooler Clear",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Pick a PDF or photo, or share from any Android app. Every file is cached locally with PWG Raster auto-conversion for HP Ink Tank 410/419.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onPickDocument,
                    shape = CircleShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("empty_new_print_button")
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New Print")
                }
                FilledTonalButton(
                    onClick = onOpenDiagnostics,
                    shape = CircleShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("empty_diag_button")
                ) {
                    Text("Nozzle Test")
                }
            }
        }
    }
}
