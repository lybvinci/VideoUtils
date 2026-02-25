package com.lybvinci.videoutils.extractor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class MediaExtractorAudioExtractor : AudioExtractor {
    override suspend fun extractAudio(
        context: Context,
        inputVideoUri: Uri,
        outputFileDescriptor: FileDescriptor,
        outputMimeType: String,
    ) {
        when (outputMimeType) {
            OUTPUT_MIME_M4A -> remuxToM4a(context, inputVideoUri, outputFileDescriptor)
            OUTPUT_MIME_MP3 -> transcodeToMp3(context, inputVideoUri, outputFileDescriptor)
            else -> throw UnsupportedOperationException("不支持的输出格式：$outputMimeType")
        }
    }

    private fun remuxToM4a(
        context: Context,
        inputVideoUri: Uri,
        outputFileDescriptor: FileDescriptor,
    ) {
        val resolver = context.contentResolver
        val inputPfd = resolver.openFileDescriptor(inputVideoUri, "r")
            ?: throw IllegalStateException("无法打开视频文件")

        try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(inputPfd.fileDescriptor)

                val (audioTrackIndex, audioFormat) = findFirstAudioTrack(extractor)
                extractor.selectTrack(audioTrackIndex)

                val useTempFile = Build.VERSION.SDK_INT < 26
                val tempFile =
                    if (useTempFile) {
                        File.createTempFile("videoutils_", ".m4a", context.cacheDir)
                    } else {
                        null
                    }

                val muxer =
                    if (useTempFile) {
                        MediaMuxer(tempFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    } else {
                        MediaMuxer(outputFileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    }
                try {
                    val muxerTrackIndex = muxer.addTrack(audioFormat)
                    muxer.start()

                    val maxInputSize = audioFormat.maxInputSizeOrNull() ?: DEFAULT_BUFFER_SIZE
                    val buffer = ByteBuffer.allocateDirect(maxInputSize)
                    val bufferInfo = MediaCodec.BufferInfo()

                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = extractor.sampleTime
                        bufferInfo.flags =
                            if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                                MediaCodec.BUFFER_FLAG_KEY_FRAME
                            } else {
                                0
                            }

                        muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                        extractor.advance()
                    }
                } finally {
                    try {
                        muxer.stop()
                    } catch (t: Throwable) {
                        Log.e(TAG, "muxer.stop failed", t)
                    }
                    muxer.release()
                }

                if (useTempFile) {
                    val outputPfd = ParcelFileDescriptor.dup(outputFileDescriptor)
                    outputPfd.use { pfd ->
                        FileInputStream(tempFile!!).use { input ->
                            FileOutputStream(pfd.fileDescriptor).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    tempFile?.delete()
                }
            } finally {
                extractor.release()
            }
        } finally {
            try {
                inputPfd.close()
            } catch (t: Throwable) {
                Log.e(TAG, "close input pfd failed: $inputVideoUri", t)
            }
        }
    }

    private fun transcodeToMp3(
        context: Context,
        inputVideoUri: Uri,
        outputFileDescriptor: FileDescriptor,
    ) {
        val resolver = context.contentResolver
        val inputPfd = resolver.openFileDescriptor(inputVideoUri, "r")
            ?: throw IllegalStateException("无法打开视频文件")

        try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(inputPfd.fileDescriptor)
                val (audioTrackIndex, audioFormat) = findFirstAudioTrack(extractor)
                extractor.selectTrack(audioTrackIndex)

                val inputMime = audioFormat.getString(MediaFormat.KEY_MIME)
                    ?: throw IllegalStateException("无法识别音轨编码")

                val decoder = MediaCodec.createDecoderByType(inputMime)
                val encoder =
                    try {
                        MediaCodec.createEncoderByType(OUTPUT_MIME_MP3)
                    } catch (t: Throwable) {
                        decoder.release()
                        throw UnsupportedOperationException("当前设备不支持 mp3 编码", t)
                    }
                val encoderBufferInfo = MediaCodec.BufferInfo()
                val decoderBufferInfo = MediaCodec.BufferInfo()

                val outputPfd = ParcelFileDescriptor.dup(outputFileDescriptor)
                outputPfd.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { output ->
                        try {
                            val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val encoderFormat = MediaFormat.createAudioFormat(OUTPUT_MIME_MP3, sampleRate, channelCount).apply {
                                setInteger(MediaFormat.KEY_BIT_RATE, DEFAULT_MP3_BITRATE)
                            }

                            decoder.configure(audioFormat, null, null, 0)
                            decoder.start()

                            try {
                                encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                                encoder.start()
                            } catch (t: Throwable) {
                                throw UnsupportedOperationException("当前设备不支持 mp3 编码", t)
                            }

                            var extractorDone = false
                            var decoderDone = false
                            var encoderDone = false
                            var encoderEosQueued = false

                            fun drainEncoder(timeoutUs: Long) {
                                while (true) {
                                    val outIndex = encoder.dequeueOutputBuffer(encoderBufferInfo, timeoutUs)
                                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) return
                                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                                    if (outIndex < 0) continue

                                    val outBuffer = encoder.getOutputBuffer(outIndex)
                                    if (outBuffer != null && encoderBufferInfo.size > 0 &&
                                        (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                                    ) {
                                        outBuffer.position(encoderBufferInfo.offset)
                                        outBuffer.limit(encoderBufferInfo.offset + encoderBufferInfo.size)
                                        val data = ByteArray(encoderBufferInfo.size)
                                        outBuffer.get(data)
                                        output.write(data)
                                    }

                                    val eos = (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                    encoder.releaseOutputBuffer(outIndex, false)
                                    if (eos) {
                                        encoderDone = true
                                        return
                                    }
                                }
                            }

                            while (!encoderDone) {
                                drainEncoder(0)

                                if (!extractorDone) {
                                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                                    if (inIndex >= 0) {
                                        val inBuffer = decoder.getInputBuffer(inIndex)
                                        if (inBuffer != null) {
                                            val sampleSize = extractor.readSampleData(inBuffer, 0)
                                            if (sampleSize < 0) {
                                                decoder.queueInputBuffer(
                                                    inIndex,
                                                    0,
                                                    0,
                                                    0,
                                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                                )
                                                extractorDone = true
                                            } else {
                                                decoder.queueInputBuffer(
                                                    inIndex,
                                                    0,
                                                    sampleSize,
                                                    extractor.sampleTime,
                                                    extractor.sampleFlags,
                                                )
                                                extractor.advance()
                                            }
                                        } else {
                                            decoder.queueInputBuffer(inIndex, 0, 0, 0, 0)
                                        }
                                    }
                                }

                                if (!decoderDone) {
                                    val outIndex = decoder.dequeueOutputBuffer(decoderBufferInfo, TIMEOUT_US)
                                    when {
                                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                                        outIndex >= 0 -> {
                                            val outBuffer = decoder.getOutputBuffer(outIndex)
                                            val isEos = (decoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                                            if (outBuffer != null && decoderBufferInfo.size > 0) {
                                                outBuffer.position(decoderBufferInfo.offset)
                                                outBuffer.limit(decoderBufferInfo.offset + decoderBufferInfo.size)

                                                val pcm = ByteArray(decoderBufferInfo.size)
                                                outBuffer.get(pcm)

                                                val bytesPerSample = 2
                                                val bytesPerFrame = bytesPerSample * channelCount

                                                var offset = 0
                                                while (offset < pcm.size) {
                                                    val encInIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                                                    if (encInIndex >= 0) {
                                                        val encInBuffer = encoder.getInputBuffer(encInIndex)
                                                        if (encInBuffer != null) {
                                                            encInBuffer.clear()
                                                            val copySize = minOf(encInBuffer.remaining(), pcm.size - offset)
                                                            encInBuffer.put(pcm, offset, copySize)

                                                            val consumedFrames = (offset / bytesPerFrame).toLong()
                                                            val pts =
                                                                decoderBufferInfo.presentationTimeUs +
                                                                    consumedFrames * 1_000_000L / sampleRate.toLong()

                                                            encoder.queueInputBuffer(
                                                                encInIndex,
                                                                0,
                                                                copySize,
                                                                pts,
                                                                0,
                                                            )
                                                            offset += copySize
                                                        } else {
                                                            encoder.queueInputBuffer(encInIndex, 0, 0, 0, 0)
                                                        }
                                                    } else {
                                                        drainEncoder(0)
                                                    }
                                                }
                                            }

                                            decoder.releaseOutputBuffer(outIndex, false)

                                            if (isEos) {
                                                decoderDone = true
                                            }
                                        }
                                    }
                                }

                                if (decoderDone && !encoderEosQueued) {
                                    var queued = false
                                    while (!queued) {
                                        val encInIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                                        if (encInIndex >= 0) {
                                            encoder.queueInputBuffer(
                                                encInIndex,
                                                0,
                                                0,
                                                0,
                                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                            )
                                            encoderEosQueued = true
                                            queued = true
                                        } else {
                                            drainEncoder(0)
                                        }
                                    }
                                }

                                drainEncoder(TIMEOUT_US)
                            }

                            output.flush()
                        } finally {
                            try {
                                decoder.stop()
                            } catch (t: Throwable) {
                                Log.e(TAG, "decoder.stop failed", t)
                            }
                            try {
                                encoder.stop()
                            } catch (t: Throwable) {
                                Log.e(TAG, "encoder.stop failed", t)
                            }
                            decoder.release()
                            encoder.release()
                        }
                    }
                }
            } finally {
                extractor.release()
            }
        } finally {
            try {
                inputPfd.close()
            } catch (t: Throwable) {
                Log.e(TAG, "close input pfd failed: $inputVideoUri", t)
            }
        }
    }

    private fun findFirstAudioTrack(extractor: MediaExtractor): Pair<Int, MediaFormat> {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mimeType = format.getString(MediaFormat.KEY_MIME)
            if (mimeType != null && mimeType.startsWith("audio/")) {
                return i to format
            }
        }
        throw IllegalStateException("该视频不包含音轨")
    }

    private fun MediaFormat.maxInputSizeOrNull(): Int? {
        return if (containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else null
    }

    private companion object {
        const val TAG = "MediaExtractorAudioExtractor"
        const val DEFAULT_BUFFER_SIZE = 1024 * 1024
        const val DEFAULT_MP3_BITRATE = 128_000
        const val OUTPUT_MIME_M4A = "audio/mp4"
        const val OUTPUT_MIME_MP3 = "audio/mpeg"
        const val TIMEOUT_US = 10_000L
    }
}
