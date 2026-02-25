package com.lybvinci.videoutils.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lybvinci.videoutils.R
import com.lybvinci.videoutils.extractor.AudioExtractor
import com.lybvinci.videoutils.extractor.MediaExtractorAudioExtractor
import com.lybvinci.videoutils.settings.OutputDirectory
import com.lybvinci.videoutils.settings.ThemeMode
import com.lybvinci.videoutils.settings.UserPreferencesRepository
import com.lybvinci.videoutils.storage.AudioOutputDestination
import com.lybvinci.videoutils.storage.MediaStoreAudioDestination
import com.lybvinci.videoutils.storage.TreeUriAudioDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OutputResult(
    val displayName: String,
    val outputPath: String,
)

data class ExtractAudioUiState(
    val selectedVideoUris: List<Uri> = emptyList(),
    val selectedVideoNames: List<String> = emptyList(),
    val outputDirectory: OutputDirectory = OutputDirectory.DefaultDocuments,
    val outputDirectoryLabel: String = "",
    val themeMode: ThemeMode = ThemeMode.System,
    val isExtracting: Boolean = false,
    val outputResults: List<OutputResult> = emptyList(),
)

sealed interface ExtractAudioUiEvent {
    data class Snackbar(val message: String) : ExtractAudioUiEvent
}

class ExtractAudioViewModel(application: Application) : AndroidViewModel(application) {
    private val preferencesRepository = UserPreferencesRepository(application.applicationContext)
    private val audioExtractor: AudioExtractor = MediaExtractorAudioExtractor()

    private val selectedVideoUris = MutableStateFlow<List<Uri>>(emptyList())
    private val selectedVideoNames = MutableStateFlow<List<String>>(emptyList())
    private val isExtracting = MutableStateFlow(false)
    private val outputResults = MutableStateFlow<List<OutputResult>>(emptyList())

    private val _events = MutableSharedFlow<ExtractAudioUiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    val uiState: StateFlow<ExtractAudioUiState> =
        combine(
            selectedVideoUris,
            selectedVideoNames,
            preferencesRepository.outputDirectory,
            preferencesRepository.themeMode,
            isExtracting,
        ) { videoUris, videoNames, outputDir, themeMode, extracting ->
            val label = outputDirLabel(outputDir)
            ExtractAudioUiState(
                selectedVideoUris = videoUris,
                selectedVideoNames = videoNames,
                outputDirectory = outputDir,
                outputDirectoryLabel = label,
                themeMode = themeMode,
                isExtracting = extracting,
            )
        }.combine(outputResults) { state, results ->
            state.copy(outputResults = results)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExtractAudioUiState())

    fun onVideosSelected(uris: List<Uri>?, persistReadPermission: Boolean = false) {
        val selected = uris.orEmpty()
        selectedVideoUris.value = selected
        selectedVideoNames.value = emptyList()
        outputResults.value = emptyList()

        if (selected.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            if (persistReadPermission) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                selected.forEach { uri ->
                    try {
                        getApplication<Application>().contentResolver.takePersistableUriPermission(uri, flags)
                    } catch (t: Throwable) {
                        Log.e(TAG, "takePersistableUriPermission(read) failed: $uri", t)
                    }
                }
            }

            val names =
                selected.map { uri ->
                    resolveDisplayName(uri) ?: uri.toString()
                }
            selectedVideoNames.value = names
        }
    }

    fun onOutputDirectoryPicked(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val primaryDir = resolvePrimaryExternalStorageDirectory(treeUri)
            if (primaryDir == Environment.DIRECTORY_PICTURES) {
                preferencesRepository.setDefaultOutputDirectory(OutputDirectory.DefaultPictures)
                return@launch
            }
            if (primaryDir == Environment.DIRECTORY_DOCUMENTS) {
                preferencesRepository.setDefaultOutputDirectory(OutputDirectory.DefaultDocuments)
                return@launch
            }

            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                getApplication<Application>().contentResolver.takePersistableUriPermission(treeUri, flags)
            } catch (t: Throwable) {
                Log.e(TAG, "takePersistableUriPermission(read/write) failed: $treeUri", t)
            }
            preferencesRepository.setOutputTreeUri(treeUri)
        }
    }

    fun useDefaultOutputDirectory() {
        viewModelScope.launch(Dispatchers.IO) {
            preferencesRepository.setDefaultOutputDirectory(OutputDirectory.DefaultDocuments)
        }
    }

    fun setThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch(Dispatchers.IO) {
            preferencesRepository.setThemeMode(themeMode)
        }
    }

    fun extractAudio() {
        val inputUris = selectedVideoUris.value
        if (inputUris.isEmpty()) {
            _events.tryEmit(ExtractAudioUiEvent.Snackbar(getApplication<Application>().getString(R.string.msg_select_video_first)))
            return
        }
        if (isExtracting.value) return

        viewModelScope.launch(Dispatchers.IO) {
            isExtracting.value = true
            outputResults.value = emptyList()

            val context = getApplication<Application>().applicationContext
            val outputDir = uiState.value.outputDirectory
            val destination = createDestination(outputDir)

            val namesSnapshot = selectedVideoNames.value
            var successCount = 0
            var m4aFallbackNotified = false

            inputUris.forEachIndexed { index, inputUri ->
                val inputName = namesSnapshot.getOrNull(index) ?: resolveDisplayName(inputUri)
                val baseName = buildOutputBaseName(inputName)

                suspend fun attempt(mimeType: String, extension: String): OutputResult {
                    val requestedDisplayName = "$baseName.$extension"
                    val createdOutput =
                        try {
                            destination.create(context, requestedDisplayName, mimeType)
                        } catch (t: Throwable) {
                            Log.e(TAG, "create output failed: name=$requestedDisplayName mime=$mimeType", t)
                            throw IllegalStateException(getApplication<Application>().getString(R.string.msg_create_output_failed))
                        }

                    try {
                        audioExtractor.extractAudio(
                            context = context,
                            inputVideoUri = inputUri,
                            outputFileDescriptor = createdOutput.parcelFileDescriptor.fileDescriptor,
                            outputMimeType = mimeType,
                        )
                        try {
                            createdOutput.parcelFileDescriptor.close()
                        } catch (t: Throwable) {
                            Log.e(TAG, "close output pfd failed: ${createdOutput.outputPath}", t)
                        }
                        createdOutput.commit(context)
                        val result =
                            OutputResult(
                                createdOutput.displayName,
                                normalizeOutputPathForUi(createdOutput.outputPath),
                            )
                        outputResults.value = outputResults.value + result
                        _events.tryEmit(
                            ExtractAudioUiEvent.Snackbar(
                                getApplication<Application>().getString(R.string.msg_extract_success, createdOutput.displayName),
                            ),
                        )
                        return result
                    } catch (t: Throwable) {
                        Log.e(TAG, "extract failed: input=$inputUri output=${createdOutput.outputPath}", t)
                        try {
                            createdOutput.parcelFileDescriptor.close()
                        } catch (closeError: Throwable) {
                            Log.e(TAG, "close output pfd failed after error: ${createdOutput.outputPath}", closeError)
                        }
                        createdOutput.rollback(context)
                        throw t
                    }
                }

                try {
                    try {
                        attempt(OUTPUT_MIME_M4A, "m4a")
                    } catch (t: Throwable) {
                        Log.e(TAG, "m4a failed, fallback to mp3: input=$inputUri", t)
                        if (!m4aFallbackNotified) {
                            _events.tryEmit(
                                ExtractAudioUiEvent.Snackbar(
                                    getApplication<Application>().getString(R.string.msg_m4a_failed_fallback_mp3),
                                ),
                            )
                            m4aFallbackNotified = true
                        }
                        attempt(OUTPUT_MIME_MP3, "mp3")
                    }
                    successCount++
                } catch (t: Throwable) {
                    Log.e(TAG, "extractAudio failed for: $inputUri", t)
                    val nameForMsg = inputName ?: inputUri.toString()
                    _events.tryEmit(
                        ExtractAudioUiEvent.Snackbar(
                            getApplication<Application>().getString(R.string.msg_extract_failed_for, nameForMsg),
                        ),
                    )
                }
            }

            _events.tryEmit(
                ExtractAudioUiEvent.Snackbar(
                    getApplication<Application>().getString(R.string.msg_extract_batch_done, successCount, inputUris.size),
                ),
            )
            isExtracting.value = false
        }
    }

    private fun createDestination(outputDirectory: OutputDirectory): AudioOutputDestination {
        return when (outputDirectory) {
            is OutputDirectory.DefaultDocuments ->
                MediaStoreAudioDestination(
                    relativePath = "Documents/VideoUtils/",
                    publicDirectory = Environment.DIRECTORY_DOCUMENTS,
                )
            is OutputDirectory.DefaultPictures ->
                MediaStoreAudioDestination(
                    relativePath = "Pictures/VideoUtils/",
                    publicDirectory = Environment.DIRECTORY_PICTURES,
                )
            is OutputDirectory.DocumentTree -> TreeUriAudioDestination(outputDirectory.treeUri)
        }
    }

    private fun outputDirLabel(outputDirectory: OutputDirectory): String {
        return when (outputDirectory) {
            is OutputDirectory.DefaultDocuments -> {
                val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath
                "$base/VideoUtils/"
            }
            is OutputDirectory.DefaultPictures -> {
                val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath
                "$base/VideoUtils/"
            }
            is OutputDirectory.DocumentTree -> {
                val tree = DocumentFile.fromTreeUri(getApplication<Application>(), outputDirectory.treeUri)
                val name = tree?.name
                name?.let { getApplication<Application>().getString(R.string.label_custom_directory_named, it) }
                    ?: getApplication<Application>().getString(R.string.label_custom_directory)
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        val cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return it.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun buildOutputBaseName(inputDisplayName: String?): String {
        return inputDisplayName
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "audio_${System.currentTimeMillis()}"
    }

    private fun resolvePrimaryExternalStorageDirectory(treeUri: Uri): String? {
        if (treeUri.authority != "com.android.externalstorage.documents") return null
        val docId =
            try {
                DocumentsContract.getTreeDocumentId(treeUri)
            } catch (_: Throwable) {
                return null
            }
        if (!docId.startsWith("primary:")) return null
        val path = docId.removePrefix("primary:")
        val top = path.substringBefore('/')
        return when (top) {
            "Pictures" -> Environment.DIRECTORY_PICTURES
            "Documents" -> Environment.DIRECTORY_DOCUMENTS
            else -> null
        }
    }

    private fun normalizeOutputPathForUi(rawPath: String): String {
        if (!rawPath.startsWith("content://")) return rawPath

        val uri =
            try {
                Uri.parse(rawPath)
            } catch (_: Throwable) {
                return rawPath
            }

        if (uri.authority != "com.android.externalstorage.documents") return rawPath

        val docId =
            try {
                DocumentsContract.getDocumentId(uri)
            } catch (_: Throwable) {
                return rawPath
            }

        val splitIndex = docId.indexOf(':')
        if (splitIndex <= 0 || splitIndex == docId.lastIndex) return rawPath

        val volume = docId.substring(0, splitIndex)
        val relative = docId.substring(splitIndex + 1).trimStart('/')
        val base =
            if (volume == "primary") {
                "/storage/emulated/0"
            } else {
                "/storage/$volume"
            }
        return if (relative.isBlank()) base else "$base/$relative"
    }

    private companion object {
        const val TAG = "ExtractAudioViewModel"
        const val OUTPUT_MIME_MP3 = "audio/mpeg"
        const val OUTPUT_MIME_M4A = "audio/mp4"
    }
}
