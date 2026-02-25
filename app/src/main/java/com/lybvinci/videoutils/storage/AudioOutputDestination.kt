package com.lybvinci.videoutils.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor

data class CreatedAudioOutput(
    val uri: Uri,
    val displayName: String,
    val outputPath: String,
    val parcelFileDescriptor: ParcelFileDescriptor,
    val commit: suspend (Context) -> Unit,
    val rollback: suspend (Context) -> Unit,
)

interface AudioOutputDestination {
    suspend fun create(context: Context, displayName: String, mimeType: String): CreatedAudioOutput
    suspend fun label(context: Context): String
}
