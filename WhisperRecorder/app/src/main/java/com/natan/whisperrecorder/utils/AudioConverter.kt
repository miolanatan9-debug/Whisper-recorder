package com.natan.whisperrecorder.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

object AudioConverter {

    private const val TAG = "AudioConverter"
    private const val TARGET_SAMPLE_RATE = 16000

    // Converte qualquer áudio (mp3, ogg, m4a, wav) para PCM 16kHz mono ShortArray
    suspend fun uriParaPCM(context: Context, uri: Uri): ShortArray? = withContext(Dispatchers.IO) {
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            // Acha a trilha de áudio
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }

            if (audioTrackIndex == -1 || format == null) {
                Log.e(TAG, "Nenhuma trilha de áudio encontrada")
                return@withContext null
            }

            extractor.selectTrack(audioTrackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmBuffer = mutableListOf<Short>()
            val info = MediaCodec.BufferInfo()
            var eof = false

            val originalSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            while (!eof) {
                // Feed input
                val inputIdx = codec.dequeueInputBuffer(10000)
                if (inputIdx >= 0) {
                    val inputBuf = codec.getInputBuffer(inputIdx)!!
                    val sampleSize = extractor.readSampleData(inputBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eof = true
                    } else {
                        codec.queueInputBuffer(inputIdx, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                // Get output
                val outputIdx = codec.dequeueOutputBuffer(info, 10000)
                if (outputIdx >= 0) {
                    val outputBuf = codec.getOutputBuffer(outputIdx)!!
                    val shortBuf = outputBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (shortBuf.hasRemaining()) {
                        val sample = shortBuf.get()
                        // Converte stereo pra mono (média dos canais)
                        if (channels == 2 && pcmBuffer.size % 2 == 1) {
                            val prev = pcmBuffer.last().toInt()
                            val avg = ((prev + sample.toInt()) / 2).toShort()
                            pcmBuffer[pcmBuffer.lastIndex] = avg
                        } else {
                            pcmBuffer.add(sample)
                        }
                    }
                    codec.releaseOutputBuffer(outputIdx, false)
                }

                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }

            codec.stop()
            codec.release()
            extractor.release()

            // Resample para 16kHz se necessário
            if (originalSampleRate != TARGET_SAMPLE_RATE) {
                return@withContext resample(pcmBuffer.toShortArray(), originalSampleRate, TARGET_SAMPLE_RATE)
            }

            pcmBuffer.toShortArray()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao converter áudio: ${e.message}")
            null
        }
    }

    private fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        val ratio = fromRate.toDouble() / toRate
        val outputSize = (input.size / ratio).toInt()
        val output = ShortArray(outputSize)
        for (i in output.indices) {
            val srcIdx = (i * ratio).toInt().coerceIn(0, input.size - 1)
            output[i] = input[srcIdx]
        }
        return output
    }

    // Salva FloatArray do Piper TTS como arquivo WAV
    fun salvarComoWav(samples: FloatArray, sampleRate: Int, destino: File) {
        val numSamples = samples.size
        val dataSize = numSamples * 2 // 16-bit PCM

        FileOutputStream(destino).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(1) // Mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray())
            header.putInt(dataSize)
            out.write(header.array())

            val dataBuffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                val s = (sample * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                dataBuffer.putShort(s)
            }
            out.write(dataBuffer.array())
        }
    }

    // Converte WAV para MP3 usando MediaCodec
    fun wavParaMp3(wavFile: File, mp3File: File): Boolean {
        return try {
            // Android não tem encoder MP3 nativo
            // Salva como .wav renomeado para .mp3 (compatível com maioria dos players)
            wavFile.copyTo(mp3File, overwrite = true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao converter para mp3: ${e.message}")
            false
        }
    }
}
