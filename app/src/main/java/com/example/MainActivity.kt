package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BuildCircle
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Queue
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.rounded.BuildCircle
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Queue
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.DiagnosticSheetPreviewDialog
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.PrintComposerScreen
import com.example.ui.screens.PrintersScreen
import com.example.ui.screens.QueueScreen
import com.example.ui.theme.InkFlowTheme
import com.example.viewmodel.InkFlowTab
import com.example.viewmodel.InkFlowViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: InkFlowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            InkFlowTheme {
                InkFlowApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_SEND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    viewModel.importDocumentUri(uri, intent.type, switchToPrintTab = true)
                }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    viewModel.importDocumentUri(uri, intent.type, switchToPrintTab = true)
                }
            }
        }
    }
}

private data class NavItemSpec(
    val tab: InkFlowTab,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val navItems = listOf(
    NavItemSpec(InkFlowTab.QUEUE, "Queue", Icons.Rounded.Queue, Icons.Outlined.Queue, "nav_queue"),
    NavItemSpec(InkFlowTab.PRINT, "Print", Icons.Rounded.Print, Icons.Outlined.Print, "nav_print"),
    NavItemSpec(InkFlowTab.DIAGNOSTICS, "Lab", Icons.Rounded.BuildCircle, Icons.Outlined.BuildCircle, "nav_diagnostics"),
    NavItemSpec(InkFlowTab.PRINTERS, "Printers", Icons.Rounded.Router, Icons.Outlined.Router, "nav_printers")
)

@Composable
private fun InkFlowApp(viewModel: InkFlowViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    // Back press on secondary tabs cleanly returns to the primary Queue screen
    BackHandler(enabled = uiState.selectedTab != InkFlowTab.QUEUE) {
        viewModel.selectTab(InkFlowTab.QUEUE)
    }

    val queueBadgeCount = uiState.activeJobsCount + uiState.pausedOrBlockedCount

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 680.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(
                        modifier = Modifier.navigationBarsPadding(),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 4.dp
                    ) {
                        navItems.forEach { item ->
                            val selected = uiState.selectedTab == item.tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(item.tab) },
                                modifier = Modifier.testTag(item.testTag),
                                icon = {
                                    if (item.tab == InkFlowTab.QUEUE && queueBadgeCount > 0) {
                                        BadgedBox(badge = { Badge { Text(queueBadgeCount.toString()) } }) {
                                            Icon(
                                                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                                contentDescription = item.label
                                            )
                                        }
                                    } else {
                                        Icon(
                                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                            contentDescription = item.label
                                        )
                                    }
                                },
                                label = { Text(item.label) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        navItems.forEach { item ->
                            val selected = uiState.selectedTab == item.tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(item.tab) },
                                modifier = Modifier.testTag(item.testTag),
                                icon = {
                                    if (item.tab == InkFlowTab.QUEUE && queueBadgeCount > 0) {
                                        BadgedBox(badge = { Badge { Text(queueBadgeCount.toString()) } }) {
                                            Icon(
                                                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                                contentDescription = item.label
                                            )
                                        }
                                    } else {
                                        Icon(
                                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                            contentDescription = item.label
                                        )
                                    }
                                },
                                label = { Text(item.label) }
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val contentModifier = Modifier.widthIn(max = 760.dp)
                    when (uiState.selectedTab) {
                        InkFlowTab.QUEUE -> QueueScreen(
                            uiState = uiState,
                            onRefreshNetwork = viewModel::refreshNetworkState,
                            onProbePrinter = viewModel::probeSelectedPrinter,
                            onPauseJob = viewModel::pauseJob,
                            onResumeJob = viewModel::resumeOrUnblockJob,
                            onCancelJob = viewModel::cancelJob,
                            onDeleteJob = viewModel::deleteJob,
                            onPauseAll = viewModel::pauseAllJobs,
                            onResumeAll = viewModel::resumeAllJobs,
                            onClearDone = viewModel::clearFinishedJobs,
                            onNavigateToPrint = { viewModel.selectTab(InkFlowTab.PRINT) },
                            onNavigateToDiagnostics = { viewModel.selectTab(InkFlowTab.DIAGNOSTICS) },
                            onNavigateToPrinters = { viewModel.selectTab(InkFlowTab.PRINTERS) },
                            modifier = contentModifier
                        )

                        InkFlowTab.PRINT -> PrintComposerScreen(
                            uiState = uiState,
                            onPickUri = { uri, mime -> viewModel.importDocumentUri(uri, mime, switchToPrintTab = false) },
                            onClearDocument = viewModel::clearComposerDocument,
                            onCopiesDelta = viewModel::updateComposerCopies,
                            onColorModeChange = viewModel::updateComposerColorMode,
                            onQualityChange = viewModel::updateComposerQuality,
                            onPaperSizeChange = viewModel::updateComposerPaperSize,
                            onFormatChange = viewModel::updateComposerFormat,
                            onQueueAndPrint = { viewModel.submitComposerJob(startPaused = false) },
                            onQueuePaused = { viewModel.submitComposerJob(startPaused = true) },
                            modifier = contentModifier
                        )

                        InkFlowTab.DIAGNOSTICS -> DiagnosticsScreen(
                            uiState = uiState,
                            onProbeNow = viewModel::probeSelectedPrinter,
                            onPrintTestPage = viewModel::printDiagnosticNozzlePage,
                            onPreviewTestPage = viewModel::openDiagnosticSheetPreview,
                            onCleanPrinthead = viewModel::triggerPrintheadClean,
                            onAlignPrinthead = viewModel::triggerPrintheadAlign,
                            onHardwareResume = viewModel::sendHardwareResumePrinter,
                            onSaveVisualInk = viewModel::updateVisualInkLevels,
                            onClearLogs = viewModel::clearDiagnosticLogs,
                            modifier = contentModifier
                        )

                        InkFlowTab.PRINTERS -> PrintersScreen(
                            uiState = uiState,
                            onRefreshNetwork = viewModel::refreshNetworkState,
                            onToggleMdnsScan = viewModel::toggleMdnsScan,
                            onSaveDiscovered = viewModel::saveAndSelectDiscovered,
                            onAddManualPrinter = viewModel::addManualPrinter,
                            onSelectPrinter = viewModel::selectPrinter,
                            onDeletePrinter = viewModel::deletePrinter,
                            onToggleForceWifiBinding = viewModel::setForceWifiSocketBinding,
                            onToggleAutoResume = viewModel::setAutoResumeOnWifi,
                            modifier = contentModifier
                        )
                    }
                }
            }
        }
    }

    uiState.diagnosticSheetPreview?.let { bmp ->
        DiagnosticSheetPreviewDialog(
            bitmap = bmp,
            onDismiss = viewModel::closeDiagnosticSheetPreview,
            onPrintNow = viewModel::printDiagnosticNozzlePage
        )
    }
}
