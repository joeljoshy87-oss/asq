package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.CaptureSource
import com.example.model.EngineStatus
import com.example.ui.screens.ConsoleScreen
import com.example.ui.screens.EngineScreen
import com.example.ui.screens.TuningScreen
import com.example.ui.theme.LocalSubPulseColors
import com.example.ui.theme.SubPulseTheme
import com.example.viewmodel.SubwooferViewModel

private enum class SubPulseTab(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    CONSOLE(R.string.nav_console, Icons.Filled.GraphicEq, Icons.Outlined.GraphicEq, "nav_console"),
    TUNING(R.string.nav_tuning, Icons.Filled.Tune, Icons.Outlined.Tune, "nav_tuning"),
    ENGINE(R.string.nav_engine, Icons.Filled.Memory, Icons.Outlined.Memory, "nav_engine")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            SubPulseTheme {
                SubPulseApp()
            }
        }
    }
}

@Composable
private fun SubPulseApp(
    viewModel: SubwooferViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var currentTab by rememberSaveable { mutableStateOf(SubPulseTab.CONSOLE) }
    var pendingCaptureSource by remember { mutableStateOf<CaptureSource?>(null) }

    val colorScheme = MaterialTheme.colorScheme
    val signalColors = LocalSubPulseColors.current

    // Return to Console tab on system back when on secondary tabs
    BackHandler(enabled = currentTab != SubPulseTab.CONSOLE) {
        currentTab = SubPulseTab.CONSOLE
    }

    // MediaProjection consent launcher for capturing background audio from Spotify/YouTube/any app
    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            viewModel.startPlaybackCaptureService(result.resultCode, data)
        } else {
            viewModel.reportError(context.getString(R.string.error_projection_denied))
        }
    }

    // Launch capture after runtime permissions are satisfied
    fun launchCaptureForSource(source: CaptureSource) {
        when (source) {
            CaptureSource.STUDIO_SUB_LAB -> {
                viewModel.startSubLabService()
            }
            CaptureSource.LIVE_MICROPHONE -> {
                viewModel.startLiveMicService()
            }
            CaptureSource.INTERNAL_APP_AUDIO -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    viewModel.reportError(context.getString(R.string.error_api_playback_capture))
                    viewModel.selectSource(CaptureSource.LIVE_MICROPHONE)
                    viewModel.startLiveMicService()
                } else {
                    val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projectionLauncher.launch(mgr.createScreenCaptureIntent())
                }
            }
        }
    }

    // Runtime permissions launcher (RECORD_AUDIO + POST_NOTIFICATIONS on Android 13+)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val source = pendingCaptureSource ?: uiState.selectedSource
        pendingCaptureSource = null

        val needsAudio = source != CaptureSource.STUDIO_SUB_LAB
        val audioGranted = !needsAudio ||
            results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (audioGranted) {
            launchCaptureForSource(source)
        } else {
            viewModel.reportError(context.getString(R.string.error_mic_permission))
        }
    }

    fun requestPermissionsAndStart(source: CaptureSource) {
        val needed = mutableListOf<String>()
        if (source != CaptureSource.STUDIO_SUB_LAB &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isEmpty()) {
            launchCaptureForSource(source)
        } else {
            pendingCaptureSource = source
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    fun handleToggleEngine() {
        if (uiState.status == EngineStatus.RUNNING) {
            viewModel.stopSubwooferService()
        } else {
            requestPermissionsAndStart(uiState.selectedSource)
        }
    }

    fun handleSourceSwitch(source: CaptureSource) {
        val wasRunning = uiState.status == EngineStatus.RUNNING
        val previous = uiState.selectedSource
        viewModel.selectSource(source)
        if (wasRunning && source != previous) {
            requestPermissionsAndStart(source)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar(
                        containerColor = colorScheme.surface,
                        tonalElevation = 0.dp
                    ) {
                        SubPulseTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(tab.labelRes),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = signalColors.bass,
                                    selectedTextColor = signalColors.bass,
                                    indicatorColor = signalColors.bassContainer,
                                    unselectedIconColor = colorScheme.onSurfaceVariant,
                                    unselectedTextColor = colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.testTag(tab.testTag)
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
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = colorScheme.surface
                    ) {
                        SubPulseTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(tab.labelRes),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = signalColors.bass,
                                    selectedTextColor = signalColors.bass,
                                    indicatorColor = signalColors.bassContainer,
                                    unselectedIconColor = colorScheme.onSurfaceVariant,
                                    unselectedTextColor = colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                val contentModifier = Modifier.weight(1f)
                when (currentTab) {
                    SubPulseTab.CONSOLE -> ConsoleScreen(
                        uiState = uiState,
                        onSelectSource = ::handleSourceSwitch,
                        onToggleEngine = ::handleToggleEngine,
                        onToggleHapticMute = viewModel::toggleHapticMute,
                        onSelectPreset = viewModel::applyPreset,
                        onSelectSubLabPattern = viewModel::selectSubLabPattern,
                        onTriggerKickPad = viewModel::triggerManualKickPad,
                        onSetBassPadHeld = viewModel::setManualBassHeld,
                        onDismissError = viewModel::clearError,
                        modifier = contentModifier
                    )

                    SubPulseTab.TUNING -> TuningScreen(
                        tuning = uiState.tuning,
                        onUpdateTuning = viewModel::updateTuning,
                        onResetDefaults = viewModel::resetTuningToDefaults,
                        modifier = contentModifier
                    )

                    SubPulseTab.ENGINE -> EngineScreen(
                        hardware = uiState.hardware,
                        telemetry = uiState.telemetry,
                        status = uiState.status,
                        onPlayKickTest = viewModel::playKickCalibration,
                        onPlayBassTest = viewModel::playBassCalibration,
                        onPlayComboTest = viewModel::playSidechainCalibration,
                        modifier = contentModifier
                    )
                }
            }
        }
    }
}
