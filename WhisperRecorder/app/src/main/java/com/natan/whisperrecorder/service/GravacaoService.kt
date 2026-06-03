package com.natan.whisperrecorder.service

import android.app.*
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.natan.whisperrecorder.activities.MainActivity
import com.natan.whisperrecorder.tts.PiperTTS
import com.natan.whisperrecorder.whisper.WhisperTranscriber
import kotlinx.coroutines.*
import java.util.concurrent.LinkedBlockingQueue

class GravacaoService : Service() {

    enum class Estado { IDLE, GRAVANDO, PROCESSANDO, FALANDO }

    inner class GravacaoBinder : Binder() {
        fun getService(): GravacaoService = this@GravacaoService
    }

    private val binder = GravacaoBinder()
    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var audioRecord: AudioRecord? = null
    private var estado = Estado.IDLE

    private var transcriber: WhisperTranscriber? = null
    private var piper: PiperTTS? = null

    // Fila de processamento — evita IA concorrente
    private val filaAudio = LinkedBlockingQueue<ShortArray>(5)
    private var filaJob: Job? = null

    var ttsAtivo = true
    var onTranscricao: ((String) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onEstado: ((Estado) -> Unit)? = null

    companion object {
        const val CHANNEL_ID        = "whisper_gravacao"
        const val NOTIF_ID          = 1
        const val SAMPLE_RATE       = 16000
        const val CHUNK_SEGUNDOS    = 30
        const val OVERLAP_SEGUNDOS  = 2
        const val CHUNK_SAMPLES     = SAMPLE_RATE * CHUNK_SEGUNDOS
        const val OVERLAP_SAMPLES   = SAMPLE_RATE * OVERLAP_SEGUNDOS
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        criarCanalNotificacao()
        transcriber = WhisperTranscriber(applicationContext)
        piper       = PiperTTS(applicationContext)

        scope.launch {
            val wOk = transcriber?.inicializar() ?: false
            val pOk = piper?.inicializar() ?: false
            Log.d("Service", "Whisper=$wOk Piper=$pOk")
        }

        iniciarFilaProcessamento()
    }

    override fun onDestroy() {
        super.onDestroy()
        pararGravacao()
        filaJob?.cancel()
        transcriber?.fechar()
        piper?.fechar()
        scope.cancel()
    }

    // ----- FILA DE PROCESSAMENTO -----

    private fun iniciarFilaProcessamento() {
        filaJob = scope.launch {
            while (isActive) {
                val audio = filaAudio.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue

                mudarEstado(Estado.PROCESSANDO)
                val texto = transcriber?.transcrever(audio) ?: continue

                if (texto.isNotBlank() && !texto.startsWith("[")) {
                    withContext(Dispatchers.Main) { onTranscricao?.invoke(texto) }

                    if (ttsAtivo) {
                        mudarEstado(Estado.FALANDO)
                        piper?.falar(texto)
                    }
                }

                mudarEstado(if (estado == Estado.FALANDO || estado == Estado.PROCESSANDO) {
                    if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                        Estado.GRAVANDO else Estado.IDLE
                } else estado)
            }
        }
    }

    // ----- GRAVAÇÃO -----

    fun iniciarGravacao() {
        if (estado == Estado.GRAVANDO) return

        val bufMin = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufMin * 4
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            onStatus?.invoke("Erro: microfone indisponível")
            return
        }

        startForeground(NOTIF_ID, criarNotificacao("Gravando..."))
        audioRecord!!.startRecording()
        mudarEstado(Estado.GRAVANDO)

        scope.launch {
            val chunk    = ShortArray(CHUNK_SAMPLES)
            val overlap  = ShortArray(OVERLAP_SAMPLES)
            var posicao  = 0

            while (estado == Estado.GRAVANDO) {
                // Lê em blocos pequenos sem usar offset incorreto
                val lido = audioRecord?.read(chunk, posicao, minOf(1600, CHUNK_SAMPLES - posicao)) ?: -1

                if (lido > 0) {
                    posicao += lido

                    if (posicao >= CHUNK_SAMPLES) {
                        // Salva overlap para próximo chunk (evita cortar frases)
                        System.arraycopy(chunk, CHUNK_SAMPLES - OVERLAP_SAMPLES, overlap, 0, OVERLAP_SAMPLES)

                        // Envia para fila se tiver espaço
                        filaAudio.offer(chunk.copyOf(posicao))

                        // Inicia próximo chunk com overlap
                        System.arraycopy(overlap, 0, chunk, 0, OVERLAP_SAMPLES)
                        posicao = OVERLAP_SAMPLES
                    }
                }
            }
        }
    }

    fun pararGravacao() {
        mudarEstado(Estado.IDLE)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        stopForeground(true)
    }

    fun estaGravando() = estado == Estado.GRAVANDO

    fun falarTexto(texto: String) {
        if (!ttsAtivo) return
        scope.launch {
            mudarEstado(Estado.FALANDO)
            piper?.falar(texto)
            mudarEstado(Estado.IDLE)
        }
    }

    // ----- HELPERS -----

    private fun mudarEstado(novo: Estado) {
        estado = novo
        val msg = when (novo) {
            Estado.IDLE        -> "Pronto"
            Estado.GRAVANDO    -> "Gravando..."
            Estado.PROCESSANDO -> "Transcrevendo..."
            Estado.FALANDO     -> "Falando..."
        }
        scope.launch(Dispatchers.Main) {
            onStatus?.invoke(msg)
            onEstado?.invoke(novo)
        }
    }

    private fun criarCanalNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CHANNEL_ID, "Whisper Recorder", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Gravação e transcrição offline" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    private fun criarNotificacao(texto: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Whisper Recorder")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
