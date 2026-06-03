package com.natan.whisperrecorder.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object PiperManager {

    private const val MODEL_URL  = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/faber/medium/pt_BR-faber-medium.onnx"
    private const val CONFIG_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/faber/medium/pt_BR-faber-medium.onnx.json"

    private const val MODEL_NAME  = "piper_faber.onnx"
    private const val CONFIG_NAME = "piper_faber.json"

    fun modelFile(context: Context)  = File(context.filesDir, MODEL_NAME)
    fun configFile(context: Context) = File(context.filesDir, CONFIG_NAME)

    fun modeloBaixado(context: Context) =
        modelFile(context).exists() && configFile(context).exists()

    suspend fun baixarModelo(
        context: Context,
        onProgresso: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .build()

            if (!modelFile(context).exists()) {
                onProgresso("Baixando voz Piper (1/2)...")
                baixarArquivo(client, MODEL_URL, modelFile(context)) { pct ->
                    onProgresso("Voz: $pct%")
                }
            }

            if (!configFile(context).exists()) {
                onProgresso("Baixando config (2/2)...")
                baixarArquivo(client, CONFIG_URL, configFile(context)) { pct ->
                    onProgresso("Config: $pct%")
                }
            }

            onProgresso("Piper pronto!")
            true
        } catch (e: Exception) {
            Log.e("PiperManager", "Erro: ${e.message}")
            modelFile(context).delete()
            configFile(context).delete()
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
