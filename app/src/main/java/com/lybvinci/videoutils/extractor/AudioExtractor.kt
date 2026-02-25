package com.lybvinci.videoutils.extractor

import android.content.Context
import android.net.Uri
import java.io.FileDescriptor

interface AudioExtractor {
    suspend fun extractAudio(
        context: Context,
        inputVideoUri: Uri,
        outputFileDescriptor: FileDescriptor,
        outputMimeType: String,
    )
}
