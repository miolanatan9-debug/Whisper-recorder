package com.natan.whisperrecorder.whisper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object ModelManager {

    private const val ENCODER_URL = "https://huggingface.co/onnx-community/whisper-tiny/resolve/main/onnx/encoder_model.onnx"
    private const val DECODER_URL = "https://huggingface.co/onnx-community/whisper-tiny/resolve/main/onnx/decoder_model_merged.onnx"
    private const val VOCAB_URL   = "https://huggingface.co/onnx-community/whisper-tiny/resolve/main/tokenizer.json"

    private const val ENCODER_NAME = "whisper_encoder.onnx"
    private const val DECODER_NAME = "whisper_decoder.onnx"
    private const val VOCAB_NAME   = "whisper_vocab.json"

    fun encoderFile(context: Context) = File(context.filesDir, ENCODER_NAME)
    fun decoderFile(context: Context) = File(context.filesDir, DECODER_NAME)
    fun vocabFile(context: Context)   = File(context.filesDir, VOCAB_NAME)

    fun modeloBaixado(context: Context) =
        encoderFile(context).exists() &&
        decoderFile(context).exists() &&
        vocabFile(context).exists()

    suspend fun baixarModelo(
        context: Context,
        onProgresso: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .build()

            if (!encoderFile(context).exists()) {
                onProgresso("Baixando encoder Whisper (1/3)...")
                baixarArquivo(client, ENCODER_URL, encoderFile(context)) { pct ->
                    onProgresso("Encoder: $pct%")
                }
            }

            if (!decoderFile(context).exists()) {
                onProgresso("Baixando decoder Whisper (2/3)...")
                baixarArquivo(client, DECODER_URL, decoderFile(context)) { pct ->
                    onProgresso("Decoder: $pct%")
                }
            }

            if (!vocabFile(context).exists()) {
                onProgresso("Baixando vocabulario (3/3)...")
                baixarArquivo(client, VOCAB_URL, vocabFile(context)) { pct ->
                    onProgresso("Vocab: $pct%")
                }
            }

            onProgresso("Whisper pronto!")
            true
        } catch (e: Exception) {
            Log.e("ModelManager", "Erro: ${e.message}")
            // Limpa arquivos corrompidos
            encoderFile(context).delete()
            decoderFile(context).delete()
            vocabFile(context).delete()
            false
        }
    }

    private fun baixarArquivo(
        client: OkHttpClient,
        url: String,
        destino: File,
        onProgresso: (Int) -> Unit
    ) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body ?: throw Exception("Resposta vazia")
            val total = body.contentLength()
            var baixado = 0L

            // Arquivo temporário — só renomeia se download completo
            val temp = File(destino.parent, destino.name + ".tmp")
            FileOutputStream(temp).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(8192)
                    var lido: Int
                    while (input.read(buf).also { lido = it } != -1) {
                        out.write(buf, 0, lido)
                        baixado += lido
                        if (total > 0) onProgresso((baixado * 100 / total).toInt())
                    }
                }
            }
            temp.renameTo(destino)
        }
    }
}
