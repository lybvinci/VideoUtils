package com.lybvinci.videoutils.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.lybvinci.videoutils.R

class TreeUriAudioDestination(private val treeUri: Uri) : AudioOutputDestination {
    override suspend fun create(
        context: Context,
        displayName: String,
        mimeType: String,
    ): CreatedAudioOutput {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("无法访问所选目录")

        val uniqueDisplayName = buildUniqueDisplayName(tree, displayName)
        val outputFile = tree.createFile(mimeType, uniqueDisplayName)
            ?: throw IllegalStateException("无法在所选目录创建文件")

        val pfd = context.contentResolver.openFileDescriptor(outputFile.uri, "w")
            ?: throw IllegalStateException("无法打开输出文件")

        return CreatedAudioOutput(
            uri = outputFile.uri,
            displayName = outputFile.name ?: uniqueDisplayName,
            outputPath = outputFile.uri.toString(),
            parcelFileDescriptor = pfd,
            commit = { _ -> },
            rollback = { ctx ->
                ctx.contentResolver.delete(outputFile.uri, null, null)
            },
        )
    }

    override suspend fun label(context: Context): String {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
        val name = tree?.name
        return name?.let { context.getString(R.string.label_custom_directory_named, it) }
            ?: context.getString(R.string.label_custom_directory)
    }

    private fun buildUniqueDisplayName(tree: DocumentFile, displayName: String): String {
        if (tree.findFile(displayName) == null) return displayName

        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
        val base = displayName.substringBeforeLast('.', missingDelimiterValue = displayName)

        var index = 1
        while (true) {
            val candidate =
                if (extension.isBlank()) {
                    "${base}_$index"
                } else {
                    "${base}_$index.$extension"
                }
            if (tree.findFile(candidate) == null) return candidate
            index++
        }
    }
}
