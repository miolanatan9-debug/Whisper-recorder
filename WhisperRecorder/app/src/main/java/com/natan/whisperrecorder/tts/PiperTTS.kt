package com.natan.whisperrecorder.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.FloatBuffer
import java.nio.LongBuffer

class PiperTTS(private val context: Context) {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var sampleRate = 22050
    private var pronto = false

    companion object {
        private const val TAG = "PiperTTS"
    }

    fun inicializar(): Boolean {
        return try {
            val config = JSONObject(PiperManager.configFile(context).readText())
            val audio = config.optJSONObject("audio")
            sampleRate = audio?.optInt("sample_rate", 22050) ?: 22050

            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            session = env!!.createSession(PiperManager.modelFile(context).absolutePath, opts)

            pronto = true
            Log.d(TAG, "Piper inicializado! sampleRate=$sampleRate")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao inicializar: ${e.message}")
            false
        }
    }

    // Retorna FloatArray com as amostras de áudio geradas
    suspend fun gerarAudio(texto: String): FloatArray = withContext(Dispatchers.Default) {
        if (!pronto || texto.isBlank()) return@withContext FloatArray(0)

        try {
            val ids = textoParaFonemas(texto)
            if (ids.isEmpty()) return@withContext FloatArray(0)
            inferir(ids)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao gerar áudio: ${e.message}")
            FloatArray(0)
        }
    }

    suspend fun falar(texto: String) = withContext(Dispatchers.Default) {
        val samples = gerarAudio(texto)
        if (samples.isNotEmpty()) tocarAudio(samples)
    }

    // Converte texto em IDs de fonemas usando mapeamento IPA básico pt-BR
    private fun textoParaFonemas(texto: String): LongArray {
        // Piper VITS usa IDs de fonemas conforme o espeak-ng
        // Aqui usamos um mapeamento simplificado baseado nos chars comuns do pt-BR
        // Os IDs reais variam por modelo — este mapeamento cobre a maioria dos casos
        val normalized = texto.lowercase()
            .replace("ção", "sao")
            .replace("ções", "soes")
            .replace("lh", "l")
            .replace("nh", "n")
            .replace("rr", "r")
            .replace("ss", "s")

        val ids = mutableListOf<Long>(0L) // pad inicial

        for (c in normalized) {
            val id = charParaFonemaId(c)
            if (id >= 0) ids.add(id.toLong())
        }

        ids.add(0L) // pad final
        return ids.toLongArray()
    }

    private fun charParaFonemaId(c: Char): Int = when (c) {
        ' '  -> 3
        'a', 'á', 'à', 'â', 'ã' -> 4
        'b'  -> 38
        'c'  -> 7
        'd'  -> 44
        'e', 'é', 'ê' -> 57
        'f'  -> 59
        'g'  -> 60
        'h'  -> 61
        'i', 'í' -> 62
        'j'  -> 64
        'k'  -> 65
        'l'  -> 66
        'm'  -> 68
        'n'  -> 70
        'o', 'ó', 'ô', 'õ' -> 71
        'p'  -> 75
        'q'  -> 76
        'r'  -> 77
        's'  -> 78
        't'  -> 81
        'u', 'ú' -> 82
        'v'  -> 83
        'w'  -> 84
        'x'  -> 85
        'y'  -> 86
        'z'  -> 87
        'ç'  -> 7  // similar ao 's'
        ','  -> 16
        '.'  -> 17
        '!'  -> 18
        '?'  -> 19
        '-'  -> 20
        '\n' -> 3
        else -> -1
    }

    private fun inferir(ids: LongArray): FloatArray {
        val env = this.env ?: return FloatArray(0)
        val session = this.session ?: return FloatArray(0)

        val inputTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())
        )
        val lengthTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1)
        )
        // noise_scale=0.667, length_scale=1.0, noise_scale_w=0.8
        val scalesTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(floatArrayOf(0.667f, 1.0f, 0.8f)), longArrayOf(3)
        )
        val sidTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(0L)), longArrayOf(1)
        )

        val inputs = mapOf(
            "input"         to inputTensor,
            "input_lengths" to lengthTensor,
            "scales"        to scalesTensor,
            "sid"           to sidTensor
        )

        return try {
            val result = session.run(inputs)
            @Suppress("UNCHECKED_CAST")
            val output = result[0].value as Array<Array<FloatArray>>
            output[0][0]
        } finally {
            inputTensor.close()
            lengthTensor.close()
            scalesTensor.close()
            sidTensor.close()
        }
    }

    fun getSampleRate() = sampleRate

    private fun tocarAudio(samples: FloatArray) {
        val bufSize = maxOf(
            AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT),
            samples.size * 4
        )

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            track.play()
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + 4096, samples.size)
                track.write(samples, offset, end - offset, AudioTrack.WRITE_BLOCKING)
                offset = end
            }
            track.stop()
        } finally {
            track.release()
        }
    }

    fun fechar() {
        session?.close()
        env?.close()
        pronto = false
    }
}
