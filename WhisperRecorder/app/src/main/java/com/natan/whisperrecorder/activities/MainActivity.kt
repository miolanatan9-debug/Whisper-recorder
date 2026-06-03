package com.natan.whisperrecorder.activities

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.natan.whisperrecorder.R
import com.natan.whisperrecorder.service.GravacaoService
import com.natan.whisperrecorder.tts.PiperManager
import com.natan.whisperrecorder.tts.PiperTTS
import com.natan.whisperrecorder.utils.AudioConverter
import com.natan.whisperrecorder.whisper.ModelManager
import com.natan.whisperrecorder.whisper.WhisperTranscriber
import kotlinx.coroutines.*
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvTranscricao: TextView
    private lateinit var btnGravar: Button
    private lateinit var btnImportar: Button
    private lateinit var btnLimpar: Button
    private lateinit var btnSalvarTxt: Button
    private lateinit var btnSalvarMp3: Button
    private lateinit var btnFalar: Button
    private lateinit var switchTTS: Switch
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgresso: TextView
    private lateinit var scrollView: ScrollView

    private var gravacaoService: GravacaoService? = null
    private var serviceBound = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val transcricaoCompleta = StringBuilder()

    // Último áudio gerado pelo TTS para salvar como mp3
    private var ultimoAudioTTS: FloatArray? = null
    private var ttsSampleRate = 22050

    // Picker de arquivo de áudio
    private val pickAudio = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importarAudio(it) }
    }

    private val conexaoServico = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as GravacaoService.GravacaoBinder
            gravacaoService = b.getService()
            serviceBound = true
            gravacaoService?.ttsAtivo = switchTTS.isChecked

            gravacaoService?.onStatus = { status ->
                runOnUiThread { tvStatus.text = status }
            }
            gravacaoService?.onTranscricao = { texto ->
                runOnUiThread { adicionarTexto(texto) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            gravacaoService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus      = findViewById(R.id.tvStatus)
        tvTranscricao = findViewById(R.id.tvTranscricao)
        btnGravar     = findViewById(R.id.btnGravar)
        btnImportar   = findViewById(R.id.btnImportar)
        btnLimpar     = findViewById(R.id.btnLimpar)
        btnSalvarTxt  = findViewById(R.id.btnSalvarTxt)
        btnSalvarMp3  = findViewById(R.id.btnSalvarMp3)
        btnFalar      = findViewById(R.id.btnFalar)
        switchTTS     = findViewById(R.id.switchTTS)
        progressBar   = findViewById(R.id.progressBar)
        tvProgresso   = findViewById(R.id.tvProgresso)
        scrollView    = findViewById(R.id.scrollView)

        btnGravar.setOnClickListener { toggleGravacao() }
        btnImportar.setOnClickListener { pickAudio.launch("audio/*") }
        btnLimpar.setOnClickListener {
            transcricaoCompleta.clear()
            tvTranscricao.text = ""
            ultimoAudioTTS = null
        }
        btnSalvarTxt.setOnClickListener { salvarComoTxt() }
        btnSalvarMp3.setOnClickListener { salvarComoMp3() }
        btnFalar.setOnClickListener { lerTudo() }
        switchTTS.setOnCheckedChangeListener { _, checked ->
            gravacaoService?.ttsAtivo = checked
        }

        verificarPermissoes()
    }

    // ----- IMPORTAR ÁUDIO -----

    private fun importarAudio(uri: Uri) {
        val nome = getNomeArquivo(uri)
        tvStatus.text = "Importando: $nome"
        progressBar.visibility = ProgressBar.VISIBLE
        btnImportar.isEnabled = false
        btnGravar.isEnabled = false

        scope.launch {
            withContext(Dispatchers.IO) {
                val pcm = AudioConverter.uriParaPCM(applicationContext, uri)
                withContext(Dispatchers.Main) {
                    if (pcm == null || pcm.isEmpty()) {
                        tvStatus.text = "Erro ao ler áudio"
                        progressBar.visibility = ProgressBar.GONE
                        btnImportar.isEnabled = true
                        btnGravar.isEnabled = true
                        return@withContext
                    }

                    tvStatus.text = "Transcrevendo..."
                    // Usa o transcriber diretamente (sem gravar)
                    val transcriber = WhisperTranscriber(applicationContext)
                    if (!transcriber.inicializar()) {
                        tvStatus.text = "Erro ao inicializar Whisper"
                        progressBar.visibility = ProgressBar.GONE
                        btnImportar.isEnabled = true
                        btnGravar.isEnabled = true
                        return@withContext
                    }

                    // Processa em chunks de 30s
                    val chunkSize = GravacaoService.CHUNK_SAMPLES
                    var offset = 0
                    while (offset < pcm.size) {
                        val end = minOf(offset + chunkSize, pcm.size)
                        val chunk = pcm.copyOfRange(offset, end)
                        val texto = transcriber.transcrever(chunk)
                        if (texto.isNotBlank() && !texto.startsWith("[")) {
                            adicionarTexto(texto)
                        }
                        offset = end
                    }

                    transcriber.fechar()
                    tvStatus.text = "Transcrição concluída!"
                    progressBar.visibility = ProgressBar.GONE
                    btnImportar.isEnabled = true
                    btnGravar.isEnabled = true
                }
            }
        }
    }

    private fun getNomeArquivo(uri: Uri): String {
        var nome = "áudio"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) nome = cursor.getString(idx)
        }
        return nome
    }

    // ----- SALVAR TXT -----

    private fun salvarComoTxt() {
        val texto = transcricaoCompleta.toString()
        if (texto.isBlank()) {
            Toast.makeText(this, "Nenhum texto para salvar", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val file = File(getExternalFilesDir(null), "transcricao_${System.currentTimeMillis()}.txt")
            file.writeText(texto)
            Toast.makeText(this, "Salvo: ${file.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erro: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ----- SALVAR MP3 (WAV via Piper TTS) -----

    private fun lerTudo() {
        val texto = transcricaoCompleta.toString()
        if (texto.isBlank()) {
            Toast.makeText(this, "Nenhum texto para ler", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "Gerando áudio..."
        scope.launch {
            val piper = PiperTTS(applicationContext)
            if (!piper.inicializar()) {
                tvStatus.text = "Erro ao inicializar TTS"
                return@launch
            }
            val audio = piper.gerarAudio(texto)
            ttsSampleRate = piper.getSampleRate()
            piper.fechar()

            if (audio.isNotEmpty()) {
                ultimoAudioTTS = audio
                tvStatus.text = "Reproduzindo..."
                // Usa o service se vinculado, senão toca direto
                gravacaoService?.falarTexto(texto) ?: run {
                    withContext(Dispatchers.IO) { piper.falar(texto) }
                }
                tvStatus.text = "Pronto"
            } else {
                tvStatus.text = "Erro ao gerar áudio"
            }
        }
    }

    private fun salvarComoMp3() {
        val audio = ultimoAudioTTS
        if (audio == null || audio.isEmpty()) {
            Toast.makeText(this, "Clique em 'Ler Tudo' primeiro para gerar o áudio", Toast.LENGTH_LONG).show()
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val wavFile = File(cacheDir, "tts_temp.wav")
                AudioConverter.salvarComoWav(audio, ttsSampleRate, wavFile)

                val mp3File = File(
                    getExternalFilesDir(null),
                    "tts_${System.currentTimeMillis()}.mp3"
                )
                AudioConverter.wavParaMp3(wavFile, mp3File)
                wavFile.delete()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Salvo: ${mp3File.name}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Erro ao salvar: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ----- GRAVAÇÃO -----

    private fun toggleGravacao() {
        val service = gravacaoService ?: return
        if (service.estaGravando()) {
            service.pararGravacao()
            btnGravar.text = "Iniciar Gravacao"
        } else {
            val intent = Intent(this, GravacaoService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)

            // Aguarda um frame antes de iniciar para o service estar pronto
            btnGravar.postDelayed({
                service.iniciarGravacao()
                btnGravar.text = "Parar Gravacao"
            }, 300)
        }
    }

    // ----- HELPERS -----

    private fun adicionarTexto(texto: String) {
        transcricaoCompleta.append(texto).append("\n\n")
        tvTranscricao.text = transcricaoCompleta.toString()
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun verificarPermissoes() {
        val permissoes = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissoes.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val faltando = permissoes.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (faltando.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, faltando.toTypedArray(), 100)
        } else {
            verificarModelos()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            verificarModelos()
        } else {
            tvStatus.text = "Permissao negada"
        }
    }

    private fun verificarModelos() {
        val whisperOk = ModelManager.modeloBaixado(this)
        val piperOk   = PiperManager.modeloBaixado(this)

        if (whisperOk && piperOk) {
            tvStatus.text = "Pronto!"
            btnGravar.isEnabled = true
            btnImportar.isEnabled = true
            vincularServico()
        } else {
            baixarModelos(whisperOk, piperOk)
        }
    }

    private fun baixarModelos(whisperOk: Boolean, piperOk: Boolean) {
        btnGravar.isEnabled = false
        btnImportar.isEnabled = false
        progressBar.visibility = ProgressBar.VISIBLE
        tvProgresso.visibility = TextView.VISIBLE

        scope.launch {
            var ok = true

            if (!whisperOk) {
                ok = ModelManager.baixarModelo(this@MainActivity) { prog ->
                    tvProgresso.text = prog; tvStatus.text = prog
                }
            }
            if (ok && !piperOk) {
                ok = PiperManager.baixarModelo(this@MainActivity) { prog ->
                    tvProgresso.text = prog; tvStatus.text = prog
                }
            }

            progressBar.visibility = ProgressBar.GONE
            tvProgresso.visibility = TextView.GONE

            if (ok) {
                tvStatus.text = "Modelos prontos!"
                btnGravar.isEnabled = true
                btnImportar.isEnabled = true
                vincularServico()
            } else {
                tvStatus.text = "Erro ao baixar modelos"
            }
        }
    }

    private fun vincularServico() {
        val intent = Intent(this, GravacaoService::class.java)
        bindService(intent, conexaoServico, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) unbindService(conexaoServico)
        scope.cancel()
    }
}
