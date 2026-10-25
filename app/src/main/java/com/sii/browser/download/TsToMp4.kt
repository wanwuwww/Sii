package com.sii.browser.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * MPEG-TS → MP4 无损重封装：只改容器，不重新编码。
 * 用于 HLS 分片拼接后的 .ts，失败时由调用方保留 .ts。
 */
object TsToMp4 {

    suspend fun convert(ts: File, mp4: File): Boolean = withContext(Dispatchers.IO) {
        if (!ts.isFile || ts.length() == 0L) return@withContext false
        var temp: File? = null
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var success = false
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(ts.absolutePath)
            var videoTrack = -1
            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && videoTrack < 0) {
                    videoTrack = i
                } else if (mime.startsWith("audio/") && audioTrack < 0) {
                    audioTrack = i
                }
            }
            if (videoTrack < 0 && audioTrack < 0) return@withContext false

            mp4.parentFile?.mkdirs()
            temp = File(mp4.parentFile, mp4.name + ".part")
            if (temp.exists()) temp.delete()
            muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideo = if (videoTrack >= 0) {
                muxer.addTrack(extractor.getTrackFormat(videoTrack))
            } else -1
            val outAudio = if (audioTrack >= 0) {
                muxer.addTrack(extractor.getTrackFormat(audioTrack))
            } else -1
            if (videoTrack >= 0) extractor.selectTrack(videoTrack)
            if (audioTrack >= 0) extractor.selectTrack(audioTrack)
            muxer.start()

            val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var startUs = -1L
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val track = extractor.sampleTrackIndex
                val time = extractor.sampleTime
                if (startUs < 0L) startUs = time
                val pts = (time - startUs).coerceAtLeast(0L)
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                val outTrack = if (track == videoTrack) outVideo else outAudio
                if (outTrack >= 0) {
                    info.set(0, size, pts, flags)
                    muxer.writeSampleData(outTrack, buffer, info)
                }
                extractor.advance()
            }
            muxer.stop()
            success = temp.length() > 0L
        } catch (_: Exception) {
            success = false
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
            if (!success) temp?.delete()
        }
        if (success) {
            if (mp4.exists()) mp4.delete()
            if (temp?.renameTo(mp4) != true) {
                temp?.delete()
                return@withContext false
            }
        }
        success
    }
}
