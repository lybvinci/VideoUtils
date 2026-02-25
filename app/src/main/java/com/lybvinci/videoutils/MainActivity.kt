@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lybvinci.videoutils

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lybvinci.videoutils.settings.OutputDirectory
import com.lybvinci.videoutils.settings.ThemeMode
import com.lybvinci.videoutils.ui.ExtractAudioUiEvent
import com.lybvinci.videoutils.ui.ExtractAudioUiState
import com.lybvinci.videoutils.ui.ExtractAudioViewModel
import com.lybvinci.videoutils.ui.theme.VideoUtilsTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: ExtractAudioViewModel = viewModel()
            val state by viewModel.uiState.collectAsState()
            VideoUtilsTheme(themeMode = state.themeMode) {
                var showSettings by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = showSettings) {
                    showSettings = false
                }
                if (showSettings) {
                    SettingsRoute(
                        viewModel = viewModel,
                        state = state,
                        onBack = { showSettings = false },
                    )
                } else {
                    ExtractAudioRoute(
                        viewModel = viewModel,
                        state = state,
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SectionBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ExtractAudioRoute(
    viewModel: ExtractAudioViewModel,
    state: ExtractAudioUiState,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val openDocumentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            viewModel.onVideosSelected(uris, persistReadPermission = true)
        }

    val getContentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            viewModel.onVideosSelected(uris)
        }

    val writePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.extractAudio()
            } else {
                coroutineScope.launch { snackbarHostState.showSnackbar(context.getString(R.string.msg_write_permission_required)) }
            }
        }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ExtractAudioUiEvent.Snackbar -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    val needsLegacyWritePermission =
        Build.VERSION.SDK_INT < 29 &&
            (state.outputDirectory is OutputDirectory.DefaultDocuments || state.outputDirectory is OutputDirectory.DefaultPictures)

    val hasLegacyWritePermission =
        !needsLegacyWritePermission ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.title_home)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = context.getString(R.string.cd_open_settings),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(context.getString(R.string.section_video_file))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { openDocumentLauncher.launch(arrayOf("video/*")) }) {
                    Text(context.getString(R.string.btn_pick_from_files))
                }
                Button(onClick = { getContentLauncher.launch("video/*") }) {
                    Text(context.getString(R.string.btn_pick_from_gallery))
                }
            }

            if (state.selectedVideoUris.isNotEmpty()) {
                SectionBody(context.getString(R.string.label_selected_video_count, state.selectedVideoUris.size))
                state.selectedVideoNames.take(5).forEach { name ->
                    Text(text = name, style = MaterialTheme.typography.bodyMedium)
                }
                if (state.selectedVideoNames.size > 5) {
                    SectionBody(context.getString(R.string.label_selected_video_more, state.selectedVideoNames.size - 5))
                }
            }

            Button(
                onClick = {
                    if (hasLegacyWritePermission) {
                        viewModel.extractAudio()
                    } else {
                        writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                },
                enabled = !state.isExtracting && state.selectedVideoUris.isNotEmpty(),
            ) {
                Text(
                    if (state.isExtracting) {
                        context.getString(R.string.btn_extracting)
                    } else {
                        context.getString(R.string.btn_start_extract)
                    },
                )
            }

            if (state.outputResults.isNotEmpty()) {
                SectionTitle(context.getString(R.string.label_output_path))
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.outputResults.forEach { item ->
                            Text(
                                text = item.displayName,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            )
                            SectionBody(item.outputPath)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingsRoute(
    viewModel: ExtractAudioViewModel,
    state: ExtractAudioUiState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    val openDocumentTreeLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) viewModel.onOutputDirectoryPicked(uri)
        }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = context.getString(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(context.getString(R.string.section_output_directory))
            SectionBody(state.outputDirectoryLabel)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { openDocumentTreeLauncher.launch(null) }) {
                    Text(context.getString(R.string.btn_pick_output_directory))
                }
                Button(onClick = { viewModel.useDefaultOutputDirectory() }) {
                    Text(context.getString(R.string.btn_reset_to_default))
                }
            }

            SectionTitle(context.getString(R.string.section_theme))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val items =
                    listOf(
                        ThemeMode.System to context.getString(R.string.theme_follow_system),
                        ThemeMode.Light to context.getString(R.string.theme_light),
                        ThemeMode.Dark to context.getString(R.string.theme_dark),
                    )

                items.forEach { (mode, label) ->
                    if (state.themeMode == mode) {
                        Button(onClick = { viewModel.setThemeMode(mode) }) {
                            Text(label)
                        }
                    } else {
                        OutlinedButton(onClick = { viewModel.setThemeMode(mode) }) {
                            Text(label)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
