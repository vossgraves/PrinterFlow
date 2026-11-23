package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.PaperSize
import com.example.data.local.PrintColorMode
import com.example.data.local.PrintQuality
import com.example.data.local.TransmitFormat
import com.example.ui.components.AlertHintPill
import com.example.ui.theme.MonoFontFamily
import com.example.viewmodel.InkFlowUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrintComposerScreen(
    uiState: InkFlowUiState,
    onPickUri: (android.net.Uri, String?) -> Unit,
    onClearDocument: () -> Unit,
    onCopiesDelta: (Int) -> Unit,
    onColorModeChange: (PrintColorMode) -> Unit,
    onQualityChange: (PrintQuality) -> Unit,
    onPaperSizeChange: (PaperSize) -> Unit,
    onFormatChange: (TransmitFormat) -> Unit,
    onQueueAndPrint: () -> Unit,
    onQueuePaused: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Zero-permission SAF PDF picker + Android Photo Picker (Google Play policy compliant!)
    val pdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onPickUri(uri, "application/pdf")
    }

    val photoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onPickUri(uri, "image/jpeg")
    }

    val draft = uiState.composer

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Send to Printer",
                    style = MaterialTheme.typography.headlineLarge
                )
                Text(
                    text = "Auto-rasterizes PDFs & images into HP 410/419 compatible PWG Raster or JPEG.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Document Intake Buttons
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    onClick = { pdfLauncher.launch(arrayOf("application/pdf", "image/*")) },
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .weight(1f)
                        .height(104.dp)
                        .testTag("pick_pdf_button")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PictureAsPdf,
                            contentDescription = "Pick PDF",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text(
                                text = "Pick PDF / File",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Multi-page PDF",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                            )
                        }
                    }
                }

                Surface(
                    onClick = {
                        photoLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .weight(1f)
                        .height(104.dp)
                        .testTag("pick_photo_button")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PhotoLibrary,
                            contentDescription = "Pick Photo",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text(
                                text = "Pick Photo",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "Android Photo Picker",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f)
                            )
                        }
                    }
                }
            }
        }

        // Document Preview Card
        item {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                when {
                    draft.isLoadingFile -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(10.dp))
                                Text("Caching file into resilient local spool...")
                            }
                        }
                    }

                    draft.fileMeta != null -> {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = draft.fileMeta.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${draft.fileMeta.pageCount} page(s) • ${(draft.fileMeta.sizeBytes / 1024).coerceAtLeast(1)} KB • ${draft.fileMeta.mimeType}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = MonoFontFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = onClearDocument,
                                    modifier = Modifier.testTag("clear_draft_button")
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Remove selected file")
                                }
                            }

                            draft.previewBitmap?.let { bmp ->
                                Spacer(Modifier.height(12.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(210.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(Color.White),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "Selected document page 1 preview",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(8.dp)
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No document selected yet",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Tap Pick PDF or Pick Photo above, or use Android's Share menu from WhatsApp, Drive, or Files to send directly to InkFlow.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Print Settings Section
        item {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Copies Stepper
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Copies", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Target: ${uiState.selectedPrinter?.name ?: "None"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = { onCopiesDelta(-1) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .testTag("copies_minus_button")
                            ) {
                                Icon(Icons.Rounded.Remove, contentDescription = "Decrease copies")
                            }
                            Text(
                                text = draft.copies.toString(),
                                style = MaterialTheme.typography.headlineSmall,
                                fontFamily = MonoFontFamily,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            IconButton(
                                onClick = { onCopiesDelta(1) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .testTag("copies_plus_button")
                            ) {
                                Icon(
                                    Icons.Rounded.Add,
                                    contentDescription = "Increase copies",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    // Color Mode
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Ink Mode", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrintColorMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = draft.colorMode == mode,
                                    onClick = { onColorModeChange(mode) },
                                    label = { Text(mode.label) }
                                )
                            }
                        }
                    }

                    // Paper Size
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Paper Size", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PaperSize.entries.forEach { size ->
                                FilterChip(
                                    selected = draft.paperSize == size,
                                    onClick = { onPaperSizeChange(size) },
                                    label = { Text(size.label) }
                                )
                            }
                        }
                    }

                    // Print Quality
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Print Quality", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrintQuality.entries.forEach { q ->
                                FilterChip(
                                    selected = draft.quality == q,
                                    onClick = { onQualityChange(q) },
                                    label = { Text(q.label) }
                                )
                            }
                        }
                    }

                    // HP 410/419 Wire Format Negotiation
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("IPP Transfer Format (410/419 Compatibility)", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TransmitFormat.entries.forEach { fmt ->
                                FilterChip(
                                    selected = draft.transmitFormat == fmt,
                                    onClick = { onFormatChange(fmt) },
                                    label = { Text(fmt.label) }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!uiState.networkGuard.hasWifi) {
            item {
                AlertHintPill(
                    text = "Currently on Mobile Data/Offline — submitting will safely park your print in the local spooler and auto-send once Wi-Fi returns."
                )
            }
        }

        // Submit Buttons
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onQueuePaused,
                    enabled = draft.fileMeta != null,
                    shape = CircleShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("queue_paused_button")
                ) {
                    Icon(Icons.Rounded.PauseCircleOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add Paused")
                }

                Button(
                    onClick = onQueueAndPrint,
                    enabled = draft.fileMeta != null,
                    shape = CircleShape,
                    modifier = Modifier
                        .weight(1.4f)
                        .height(54.dp)
                        .testTag("queue_and_print_button")
                ) {
                    Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Queue & Print", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
