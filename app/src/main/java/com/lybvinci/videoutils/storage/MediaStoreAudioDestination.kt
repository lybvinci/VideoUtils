package com.lybvinci.videoutils.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File

class MediaStoreAudioDestination(
    private val relativePath: String,
    private val publicDirectory: String,
) : AudioOutputDestination {
    override suspend fun create(
        context: Context,
        displayName: String,
        mimeType: String,
    ): CreatedAudioOutput {
        return if (Build.VERSION.SDK_INT >= 29) {
            createForMediaStore29Plus(context, displayName, mimeType)
        } else {
            createForLegacyExternalStorage(context, displayName)
        }
    }

    override suspend fun label(context: Context): String {
        val base = Environment.getExternalStoragePublicDirectory(publicDirectory).absolutePath
        return "$base/VideoUtils/"
    }

    private fun createForMediaStore29Plus(
        context: Context,
        displayName: String,
        mimeType: String,
    ): CreatedAudioOutput {
        val resolver = context.contentResolver
        val uniqueDisplayName = findUniqueDisplayName29Plus(context, displayName)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueDisplayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("无法创建输出文件")

        val pfd = resolver.openFileDescriptor(uri, "w")
            ?: run {
                resolver.delete(uri, null, null)
                throw IllegalStateException("无法打开输出文件")
            }

        return CreatedAudioOutput(
            uri = uri,
            displayName = uniqueDisplayName,
            outputPath = buildAbsolutePublicPath(uniqueDisplayName),
            parcelFileDescriptor = pfd,
            commit = { ctx ->
                val commitValues =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                ctx.contentResolver.update(uri, commitValues, null, null)
            },
            rollback = { ctx ->
                ctx.contentResolver.delete(uri, null, null)
            },
        )
    }

    private fun createForLegacyExternalStorage(context: Context, displayName: String): CreatedAudioOutput {
        val baseDir = Environment.getExternalStoragePublicDirectory(publicDirectory)
        val outputDir = File(baseDir, "VideoUtils").apply { mkdirs() }
        val file = findUniqueFile(outputDir, displayName)

        val pfd =
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_READ_WRITE,
            )

        val uri = Uri.fromFile(file)

        return CreatedAudioOutput(
            uri = uri,
            displayName = file.name,
            outputPath = file.absolutePath,
            parcelFileDescriptor = pfd,
            commit = { ctx ->
                MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null, null)
            },
            rollback = { _ ->
                file.delete()
            },
        )
    }

    private fun findUniqueFile(parent: File, displayName: String): File {
        val initial = File(parent, displayName)
        if (!initial.exists()) return initial

        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
        val base = displayName.substringBeforeLast('.', missingDelimiterValue = displayName)

        var index = 1
        while (true) {
            val candidateName =
                if (extension.isBlank()) {
                    "${base}_$index"
                } else {
                    "${base}_$index.$extension"
                }
            val candidate = File(parent, candidateName)
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun findUniqueDisplayName29Plus(context: Context, displayName: String): String {
        if (!exists29Plus(context, displayName)) return displayName

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
            if (!exists29Plus(context, candidate)) return candidate
            index++
        }
    }

    private fun exists29Plus(context: Context, displayName: String): Boolean {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(displayName, relativePath)
        resolver.query(collection, projection, selection, args, null)?.use { cursor ->
            return cursor.moveToFirst()
        }
        return false
    }

    private fun buildAbsolutePublicPath(displayName: String): String {
        val base = Environment.getExternalStoragePublicDirectory(publicDirectory).absolutePath
        return "$base/VideoUtils/$displayName"
    }
}
