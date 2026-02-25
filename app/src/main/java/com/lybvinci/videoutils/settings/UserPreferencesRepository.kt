package com.lybvinci.videoutils.settings

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "videoutils_settings")

sealed interface OutputDirectory {
    data object DefaultDocuments : OutputDirectory
    data object DefaultPictures : OutputDirectory
    data class DocumentTree(val treeUri: Uri) : OutputDirectory
}

class UserPreferencesRepository(private val context: Context) {
    private object Keys {
        val defaultOutputDirectory = stringPreferencesKey("default_output_directory")
        val outputTreeUri = stringPreferencesKey("output_tree_uri")
        val themeMode = stringPreferencesKey("theme_mode")
    }

    val outputDirectory: Flow<OutputDirectory> =
        context.settingsDataStore.data.map { preferences ->
            val uriString = preferences[Keys.outputTreeUri]
            if (!uriString.isNullOrBlank()) {
                val treeUri = Uri.parse(uriString)
                val primaryDir = resolvePrimaryExternalStorageDirectory(treeUri)
                if (primaryDir == Environment.DIRECTORY_PICTURES) return@map OutputDirectory.DefaultPictures
                if (primaryDir == Environment.DIRECTORY_DOCUMENTS) return@map OutputDirectory.DefaultDocuments
                return@map OutputDirectory.DocumentTree(treeUri)
            }

            when (preferences[Keys.defaultOutputDirectory]) {
                "pictures" -> OutputDirectory.DefaultPictures
                else -> OutputDirectory.DefaultDocuments
            }
        }

    suspend fun setOutputTreeUri(treeUri: Uri?) {
        context.settingsDataStore.edit { preferences ->
            if (treeUri == null) {
                preferences.remove(Keys.outputTreeUri)
            } else {
                preferences[Keys.outputTreeUri] = treeUri.toString()
            }
        }
    }

    suspend fun setDefaultOutputDirectory(outputDirectory: OutputDirectory) {
        context.settingsDataStore.edit { preferences ->
            preferences.remove(Keys.outputTreeUri)
            preferences[Keys.defaultOutputDirectory] =
                when (outputDirectory) {
                    is OutputDirectory.DefaultDocuments -> "documents"
                    is OutputDirectory.DefaultPictures -> "pictures"
                    is OutputDirectory.DocumentTree -> "documents"
                }
        }
    }

    val themeMode: Flow<ThemeMode> =
        context.settingsDataStore.data.map { preferences ->
            when (preferences[Keys.themeMode]) {
                "light" -> ThemeMode.Light
                "dark" -> ThemeMode.Dark
                else -> ThemeMode.System
            }
        }

    suspend fun setThemeMode(themeMode: ThemeMode) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.themeMode] =
                when (themeMode) {
                    ThemeMode.System -> "system"
                    ThemeMode.Light -> "light"
                    ThemeMode.Dark -> "dark"
                }
        }
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
}
