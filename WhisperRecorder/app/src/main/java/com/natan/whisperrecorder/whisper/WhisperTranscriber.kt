package com.natan.whisperrecorder.whisper

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.*

class WhisperTranscriber(private val context: Context) {

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokenizer: WhisperTokenizer? = null
    private var pronto = false

    companion object {
        const val SAMPLE_RATE  = 16000
        const val N_FFT        = 400
        const val HOP_LENGTH   = 160
        const val N_MELS       = 80
        const val N_FRAMES     = 3000
        const val MAX_TOKENS   = 224
    }

    fun inicializar(): Boolean {
        return try {
            tokenizer = WhisperTokenizer(context)
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            encoderSession = env!!.createSession(ModelManager.encoderFile(context).absolutePath, opts)
            decoderSession = env!!.createSession(ModelManager.decoderFile(context).absolutePath, opts)
            pronto = true
            Log.d("Whisper", "Inicializado com sucesso")
            true
        } catch (e: Exception) {
            Log.e("Whisper", "Erro ao inicializar: ${e.message}")
            false
        }
    }

    suspend fun transcrever(audioData: ShortArray): String = withContext(Dispatchers.Default) {
        if (!pronto) return@withContext "[modelo não inicializado]"
        try {
            val floatAudio = FloatArray(audioData.size) { i -> audioData[i] / 32768.0f }
            val mel = extrairMelSpectrogram(floatAudio)
            val encoderOut = rodarEncoder(mel) ?: return@withContext "[erro no encoder]"
            val tokens = rodarDecoder(encoderOut)
            encoderOut.close()
            tokenizer?.decode(tokens) ?: "[erro no tokenizer]"
        } catch (e: Exception) {
            Log.e("Whisper", "Erro na transcrição: ${e.message}")
            "[erro: ${e.message}]"
        }
    }

    // ----- MEL SPECTROGRAM -----

    private fun extrairMelSpectrogram(audio: FloatArray): Array<FloatArray> {
        val paddedAudio = FloatArray(audio.size + N_FFT / 2)
        System.arraycopy(audio, 0, paddedAudio, N_FFT / 2, audio.size)

        val nFrames = (paddedAudio.size - N_FFT) / HOP_LENGTH + 1
        val fftSize = N_FFT / 2 + 1
        val filters = buildMelFilterbank(N_MELS, fftSize, SAMPLE_RATE)
        val mel = Array(N_MELS) { FloatArray(nFrames) }
        val window = FloatArray(N_FFT) { n -> (0.5 * (1 - cos(2 * PI * n / (N_FFT - 1)))).toFloat() }

        for (frame in 0 until nFrames) {
            val start = frame * HOP_LENGTH
            val windowed = FloatArray(N_FFT) { i ->
                if (start + i < paddedAudio.size) paddedAudio[start + i] * window[i] else 0f
            }
            val power = computePowerSpectrum(windowed)
            for (m in 0 until N_MELS) {
                var sum = 0f
                for (k in power.indices) sum += filters[m][k] * power[k]
                mel[m][frame] = ln(maxOf(sum, 1e-10f))
            }
        }

        var maxVal = Float.NEGATIVE_INFINITY
        for (m in 0 until N_MELS) for (f in 0 until nFrames) if (mel[m][f] > maxVal) maxVal = mel[m][f]
        for (m in 0 until N_MELS) for (f in 0 until nFrames) {
            mel[m][f] = maxOf(mel[m][f], maxVal - 8f)
            mel[m][f] = (mel[m][f] + 4f) / 4f
        }

        return mel
    }

    private fun computePowerSpectrum(windowed: FloatArray): FloatArray {
        val n = windowed.size
        val real = windowed.copyOf()
        val imag = FloatArray(n)

        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val angle = -2 * PI / len
            val wRe = cos(angle).toFloat()
            val wIm = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (j in 0 until halfLen) {
                    val uRe = real[i + j]
                    val uIm = imag[i + j]
                    val vRe = real[i + j + halfLen] * curRe - imag[i + j + halfLen] * curIm
                    val vIm = real[i + j + halfLen] * curIm + imag[i + j + halfLen] * curRe
                    real[i + j] = uRe + vRe
                    imag[i + j] = uIm + vIm
                    real[i + j + halfLen] = uRe - vRe
                    imag[i + j + halfLen] = uIm - vIm
                    val newRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = newRe
                }
                i += len
            }
            len *= 2
        }

        val half = n / 2 + 1
        return FloatArray(half) { k -> real[k] * real[k] + imag[k] * imag[k] }
    }

    private fun buildMelFilterbank(nMels: Int, fftSize: Int, sampleRate: Int): Array<FloatArray> {
        val fMin = 0.0
        val fMax = sampleRate / 2.0
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val melPoints = DoubleArray(nMels + 2) { i -> melMin + i * (melMax - melMin) / (nMels + 1) }
        val hzPoints = DoubleArray(nMels + 2) { i -> melToHz(melPoints[i]) }
        val binPoints = IntArray(nMels + 2) { i -> floor(hzPoints[i] * fftSize / sampleRate).toInt() }

        return Array(nMels) { m ->
            FloatArray(fftSize) { k ->
                when {
                    k < binPoints[m] || k > binPoints[m + 2] -> 0f
                    k < binPoints[m + 1] -> (k - binPoints[m]).toFloat() / (binPoints[m + 1] - binPoints[m])
                    else -> (binPoints[m + 2] - k).toFloat() / (binPoints[m + 2] - binPoints[m + 1])
                }
            }
        }
    }

    private fun hzToMel(hz: Double) = 2595.0 * log10(1 + hz / 700.0)
    private fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1)

    // ----- ENCODER -----

    private fun rodarEncoder(mel: Array<FloatArray>): OnnxTensor? {
        val env = this.env ?: return null
        val encoder = encoderSession ?: return null

        val nFramesMel = mel[0].size
        val buf = FloatBuffer.allocate(1 * N_MELS * N_FRAMES)

        for (m in 0 until N_MELS) {
            for (f in 0 until N_FRAMES) {
                buf.put(if (f < nFramesMel) mel[m][f] else 0f)
            }
        }
        buf.rewind()

        val tensor = OnnxTensor.createTensor(env, buf, longArrayOf(1, N_MELS.toLong(), N_FRAMES.toLong()))
        val result = encoder.run(mapOf("input_features" to tensor))
        tensor.close()

        return result[0].value as? OnnxTensor
    }

    // ----- DECODER (greedy search) -----
    // Trocado repeat{} por for loop — break funciona corretamente

    private fun rodarDecoder(encoderOutput: OnnxTensor): List<Int> {
        val env = this.env ?: return emptyList()
        val decoder = decoderSession ?: return emptyList()

        val tokens = mutableListOf(
            WhisperTokenizer.TOKEN_SOT,
            WhisperTokenizer.TOKEN_PT,
            WhisperTokenizer.TOKEN_TRANSCRIBE,
            WhisperTokenizer.TOKEN_NO_TIMESTAMPS
        )

        for (i in 0 until MAX_TOKENS) {
            val inputIds = LongBuffer.wrap(tokens.map { it.toLong() }.toLongArray())
            val inputTensor = OnnxTensor.createTensor(
                env, inputIds, longArrayOf(1, tokens.size.toLong())
            )

            val inputs = mapOf(
                "input_ids"             to inputTensor,
                "encoder_hidden_states" to encoderOutput
            )

            val result = decoder.run(inputs)
            inputTensor.close()

            @Suppress("UNCHECKED_CAST")
            val logits = (result[0].value as Array<Array<FloatArray>>)[0].last()
            val nextToken = logits.indices.maxByOrNull { logits[it] } ?: break

            if (nextToken == WhisperTokenizer.TOKEN_EOT) return tokens
            tokens.add(nextToken)
        }

        return tokens
    }

    fun fechar() {
        encoderSession?.close()
        decoderSession?.close()
        env?.close()
        pronto = false
    }
}
