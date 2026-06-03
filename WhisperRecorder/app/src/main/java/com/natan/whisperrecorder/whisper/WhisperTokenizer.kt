package com.natan.whisperrecorder.whisper

import android.content.Context
import android.util.Log
import org.json.JSONObject

class WhisperTokenizer(context: Context) {

    private val idToToken = mutableMapOf<Int, String>()
    private val tokenToId = mutableMapOf<String, Int>()

    companion object {
        const val TOKEN_SOT           = 50258
        const val TOKEN_EOT           = 50256  // <|endoftext|>
        const val TOKEN_BLANK         = 220    // espaço em BPE
        const val TOKEN_NO_TIMESTAMPS = 50363
        const val TOKEN_PT            = 50359  // <|pt|>
        const val TOKEN_TRANSCRIBE    = 50360  // <|transcribe|>
    }

    init {
        try {
            val json = JSONObject(ModelManager.vocabFile(context).readText())
            val model = json.getJSONObject("model")
            val vocab = model.getJSONObject("vocab")

            vocab.keys().forEach { token ->
                val id = vocab.getInt(token)
                idToToken[id] = token
                tokenToId[token] = id
            }

            Log.d("Tokenizer", "Carregado: ${idToToken.size} tokens")
        } catch (e: Exception) {
            Log.e("Tokenizer", "Erro ao carregar vocab: ${e.message}")
        }
    }

    fun decode(tokens: List<Int>): String {
        val sb = StringBuilder()
        for (id in tokens) {
            if (id >= 50256) continue // pula tokens especiais
            val piece = idToToken[id] ?: continue
            // Whisper BPE usa 'Ġ' (U+0120) como marcador de espaço
            sb.append(piece.replace("Ġ", " ").replace("Ċ", "\n"))
        }
        return sb.toString().trim()
    }
}
