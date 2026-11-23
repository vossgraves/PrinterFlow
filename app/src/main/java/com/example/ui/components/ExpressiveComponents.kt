package com.example.ui.components

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.JobStatus
import com.example.printer.NetworkGuardMode
import com.example.printer.NetworkGuardState
import com.example.ui.theme.LocalInkColors
import com.example.ui.theme.MonoFontFamily

@Composable
fun NetworkGuardBanner(
    networkState: NetworkGuardState,
    pausedCount: Int,
    forceWifiBinding: Boolean,
    onRefreshNetwork: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val inkColors = LocalInkColors.current

    val isWarning = networkState.mode == NetworkGuardMode.MOBILE_DATA_FALLBACK ||
        networkState.mode == NetworkGuardMode.OFFLINE

    val containerColor = when (networkState.mode) {
        NetworkGuardMode.MOBILE_DATA_FALLBACK -> MaterialTheme.colorScheme.errorContainer
        NetworkGuardMode.OFFLINE -> MaterialTheme.colorScheme.surfaceContainerHighest
        NetworkGuardMode.WIFI_AND_MOBILE_GUARDED -> MaterialTheme.colorScheme.secondaryContainer
        NetworkGuardMode.WIFI_BOUND -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    }

    val contentColor = when (networkState.mode) {
        NetworkGuardMode.MOBILE_DATA_FALLBACK -> MaterialTheme.colorScheme.onErrorContainer
        NetworkGuardMode.OFFLINE -> MaterialTheme.colorScheme.onSurface
        NetworkGuardMode.WIFI_AND_MOBILE_GUARDED -> MaterialTheme.colorScheme.onSecondaryContainer
        NetworkGuardMode.WIFI_BOUND -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    val icon = when (networkState.mode) {
        NetworkGuardMode.MOBILE_DATA_FALLBACK -> Icons.Rounded.CellTower
        NetworkGuardMode.OFFLINE -> Icons.Rounded.WifiOff
        NetworkGuardMode.WIFI_AND_MOBILE_GUARDED -> Icons.Rounded.Shield
        NetworkGuardMode.WIFI_BOUND -> Icons.Rounded.Wifi
    }

    val title = when (networkState.mode) {
        NetworkGuardMode.MOBILE_DATA_FALLBACK -> "Mobile Data Fallback Active"
        NetworkGuardMode.OFFLINE -> "No Wi-Fi Connection"
        NetworkGuardMode.WIFI_AND_MOBILE_GUARDED ->
            if (forceWifiBinding) "Wi-Fi Socket Bound (Cellular Ignored)" else "Wi-Fi + Cellular Active"
        NetworkGuardMode.WIFI_BOUND ->
            if (networkState.isLikelyWifiDirect) "HP Wi-Fi Direct Link Ready" else "Wi-Fi LAN Bound"
    }

    val subtitle = when (networkState.mode) {
        NetworkGuardMode.MOBILE_DATA_FALLBACK ->
            if (pausedCount > 0) "$pausedCount print(s) safely parked in spooler. Reconnect Wi-Fi to auto-resume."
            else "Switched to Mobile Data. Local printer IPP is paused until Wi-Fi returns."
        NetworkGuardMode.OFFLINE ->
            "Jobs stay cached on disk. Turn on Wi-Fi or HP Wi-Fi Direct to transmit."
        NetworkGuardMode.WIFI_AND_MOBILE_GUARDED ->
            "LAN IP ${networkState.wifiLocalIp ?: "Active"} • Traffic locked to Wi-Fi socket"
        NetworkGuardMode.WIFI_BOUND ->
            "Local IP ${networkState.wifiLocalIp ?: "Connected"}${if (!networkState.wifiHasInternet) " • Local-Only (Direct)" else ""}"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("network_guard_banner"),
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
        tonalElevation = if (isWarning) 4.dp else 1.dp,
        border = BorderStroke(
            1.dp,
            if (isWarning) inkColors.blocked.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(contentColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = contentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = contentColor,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onRefreshNetwork,
                    modifier = Modifier.testTag("refresh_network_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh network",
                        tint = contentColor
                    )
                }
            }

            AnimatedVisibility(
                visible = isWarning,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                Intent(Settings.Panel.ACTION_WIFI)
                            } else {
                                Intent(Settings.ACTION_WIFI_SETTINGS)
                            }
                            runCatching { context.startActivity(intent) }
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.testTag("open_wifi_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Wifi,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Switch to Printer Wi-Fi")
                    }
                }
            }
        }
    }
}

@Composable
fun JobStatusPill(status: JobStatus, modifier: Modifier = Modifier) {
    val ink = LocalInkColors.current
    val (bg, fg, label) = when (status) {
        JobStatus.QUEUED -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "QUEUED"
        )
        JobStatus.RASTERIZING -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "RASTERIZING"
        )
        JobStatus.SENDING -> Triple(
            ink.sending.copy(alpha = 0.16f),
            ink.sending,
            "SENDING IPP"
        )
        JobStatus.PRINTING_ON_DEVICE -> Triple(
            ink.online.copy(alpha = 0.18f),
            ink.online,
            "PRINTING"
        )
        JobStatus.PAUSED_BY_USER -> Triple(
            ink.paused.copy(alpha = 0.18f),
            ink.paused,
            "PAUSED"
        )
        JobStatus.PAUSED_NO_WIFI -> Triple(
            ink.paused.copy(alpha = 0.22f),
            ink.paused,
            "PARKED • NO WI-FI"
        )
        JobStatus.BLOCKED_PRINTER_ERROR -> Triple(
            ink.blocked.copy(alpha = 0.18f),
            ink.blocked,
            "BLOCKED"
        )
        JobStatus.COMPLETED -> Triple(
            ink.online.copy(alpha = 0.16f),
            ink.online,
            "SENT"
        )
        JobStatus.CANCELLED -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "CANCELLED"
        )
    }

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = bg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(fg)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                fontFamily = MonoFontFamily,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun InkTankBottleColumn(
    code: String,
    name: String,
    percent: Int,
    color: Color,
    isEstimated: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedFill by animateFloatAsState(
        targetValue = (percent.coerceIn(0, 100) / 100f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "ink_fill_$code"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(92.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(animatedFill.coerceAtLeast(0.06f))
                    .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp, topStart = 8.dp, topEnd = 8.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(color.copy(alpha = 0.82f), color)
                        )
                    )
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.labelSmall,
                color = if (percent > 30 && code != "Y") Color.White else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = code,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = MonoFontFamily,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = if (isEstimated) "Visual" else name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun DiagnosticSheetPreviewDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
    onPrintNow: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "410 / 419 Nozzle & Alignment Sheet",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Rendered at print-ready resolution for Ink Tank CMYK check",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close preview")
                    }
                }

                Spacer(Modifier.height(12.dp))

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Print quality diagnostic nozzle sheet preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .padding(6.dp),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = onDismiss,
                        shape = CircleShape,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Close")
                    }
                    Button(
                        onClick = {
                            onDismiss()
                            onPrintNow()
                        },
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1.4f)
                            .testTag("dialog_print_diag_button")
                    ) {
                        Text("Print This Sheet")
                    }
                }
            }
        }
    }
}

@Composable
fun AlertHintPill(text: String, modifier: Modifier = Modifier) {
    val ink = LocalInkColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = ink.paused.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, ink.paused.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = ink.paused,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
