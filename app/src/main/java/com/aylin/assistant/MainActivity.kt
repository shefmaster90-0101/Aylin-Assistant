package com.aylin.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnMic: Button
    private lateinit var btnSettings: Button

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            tvStatus = findViewById(R.id.tvStatus)
            tvLog = findViewById(R.id.tvLog)
            btnMic = findViewById(R.id.btnMic)
            btnSettings = findViewById(R.id.btnSettings)

            checkPermissions()
            tts = TextToSpeech(this, this)
            setupSpeechRecognizer()

            btnMic.setOnClickListener { startListening() }
            btnSettings.setOnClickListener { showSettingsDialog() }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkPermissions() {
        try {
            val perms = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE)
            if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
                ActivityCompat.requestPermissions(this, perms, 101)
            }
        } catch (_: Exception) {}
    }

    override fun onInit(status: Int) {
        try {
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                tts?.setPitch(1.25f)
                tts?.setSpeechRate(1.05f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        runOnUiThread {
                            if (utteranceId == "WAKE_GREETING") startListening()
                        }
                    }
                    override fun onError(utteranceId: String?) {}
                })
                speak("Да, Сэр.", "WAKE_GREETING")
            } else {
                tvStatus.text = "Нажмите кнопку и скажите команду"
            }
        } catch (_: Exception) {}
    }

    private fun speak(text: String, id: String = "MSG") {
        tvLog.text = "Айлин: $text\n\n${tvLog.text}"
        try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) } catch (_: Exception) {}
    }

    private fun setupSpeechRecognizer() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                tvStatus.text = "Микрофон не поддерживается"
                return
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { tvStatus.text = "🎙 Слушаю Вас, Сэр..." }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { tvStatus.text = "⏳ Обрабатываю..." }
                override fun onError(error: Int) { tvStatus.text = "Нажмите кнопку и скажите команду" }
                override fun onResults(results: Bundle?) {
                    val command = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    tvLog.text = "Сэр: $command\n${tvLog.text}"
                    processCommand(command)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        } catch (_: Exception) {}
    }

    private fun startListening() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            }
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {}
    }

    private fun processCommand(cmd: String) {
        val lower = cmd.lowercase(Locale.getDefault())
        when {
            lower == "айлин" -> speak("Да, Сэр?", "WAKE_GREETING")
            lower.contains("ютуб") || lower.contains("youtube") -> {
                val query = lower.replace("айлин", "").replace("открой", "").replace("включи", "")
                    .replace("на ютубе", "").replace("ютуб", "").replace("youtube", "").trim()
                speak("Так точно, Сэр!")
                if (query.isNotEmpty()) {
                    val intent = Intent(Intent.ACTION_SEARCH).apply {
                        setPackage("com.google.android.youtube")
                        putExtra("query", query)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try { startActivity(intent) } catch (_: Exception) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://youtube.com")))
                    }
                } else launchApp("com.google.android.youtube")
            }
            lower.contains("инстаграм") || lower.contains("instagram") -> { speak("Так точно, Сэр!"); launchApp("com.instagram.android") }
            lower.contains("телеграм") || lower.contains("telegram") -> { speak("Так точно, Сэр!"); launchApp("org.telegram.messenger") }
            lower.contains("хром") || lower.contains("браузер") -> { speak("Так точно, Сэр!"); launchApp("com.android.chrome") }
            lower.contains("галере") || lower.contains("фото") -> {
                speak("Так точно, Сэр!")
                try { startActivity(Intent(Intent.ACTION_VIEW).apply { type = "image/*"; flags = Intent.FLAG_ACTIVITY_NEW_TASK }) } catch (_: Exception) {}
            }
            lower.contains("музык") -> {
                speak("Так точно, Сэр!")
                try { startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)) } catch (_: Exception) {}
            }
            lower.contains("метатрейдер") || lower.contains("mt5") || lower.contains("график") -> {
                speak("Так точно, Сэр! Открываю MetaTrader 5.")
                launchApp("net.metaquotes.metatrader5")
            }
            else -> askGemini(cmd)
        }
    }

    private fun launchApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) startActivity(intent) else speak("Приложение не найдено, Сэр.")
        } catch (_: Exception) {}
    }

    private fun askGemini(userText: String) {
        val prefs = getSharedPreferences("AylinPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("GEMINI_KEY", "") ?: ""
        val greenUrl = prefs.getString("GREEN_URL", "") ?: ""
        if (apiKey.isEmpty()) { speak("Сэр, укажите Gemini API ключ в настройках."); return }

        tvStatus.text = "🧠 Айлин думает..."
        scope.launch(Dispatchers.IO) {
            try {
                val prompt = "Ты Айлин, личный голосовой ассистент. Обращайся к пользователю исключительно 'Сэр'. Отвечай кратко и чётко. Если Сэр просит отправить WhatsApp, верни ТОЛЬКО JSON: {\"action\":\"whatsapp\",\"phone\":\"77001234567\",\"text\":\"текст\"}. Если Сэр спрашивает про трейдинг, XAUUSD, SMC, ICT или FVG, дай краткий точный анализ."
                val bodyJson = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "$prompt\n\nЗапрос Сэра: $userText")))))
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val respStr = client.newCall(req).execute().body?.string() ?: ""
                val reply = JSONObject(respStr).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()

                withContext(Dispatchers.Main) {
                    tvStatus.text = "Готова к работе, Сэр"
                    if (reply.contains("\"action\":\"whatsapp\"")) {
                        val obj = JSONObject("{${reply.substringAfter("{").substringBeforeLast("}")}}")
                        sendWhatsApp(greenUrl, obj.getString("phone"), obj.getString("text"))
                    } else speak(reply)
                }
            } catch (_: Exception) {
        
withContext(Dispatchers.Main) { tvStatus.text = "Ошибка связи"; speak("Сэр, ошибка соединения с сервером.") }
}
}
}
private fun sendWhatsApp(greenUrl: String, phone: String, message: String) {
if (greenUrl.isEmpty()) { speak("Сэр, укажите ссылку Green-API в настройках."); return }
speak("Так точно, Сэр! Отправляю сообщение в WhatsApp.")
scope.launch(Dispatchers.IO) {
try {
val body = JSONObject().put("chatId", "${phone.replace("+", "")}@c.us").put("message", message)
client.newCall(Request.Builder().url(greenUrl).post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute()
} catch (_: Exception) {}
}
}
private fun showSettingsDialog() {
val prefs = getSharedPreferences("AylinPrefs", Context.MODE_PRIVATE)
val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 30, 40, 10) }
val etGemini = EditText(this).apply { hint = "Gemini API Key (AIzaSy...)"; setText(prefs.getString("GEMINI_KEY", "")) }
val etGreen = EditText(this).apply { hint = "Green-API sendMessage URL"; setText(prefs.getString("GREEN_URL", "")) }
layout.addView(etGemini); layout.addView(etGreen)
AlertDialog.Builder(this)
.setTitle("Настройки Айлин")
.setView(layout)
.setPositiveButton("Сохранить") { _, _ ->
prefs.edit().putString("GEMINI_KEY", etGemini.text.toString().trim()).putString("GREEN_URL", etGreen.text.toString().trim()).apply()
Toast.makeText(this, "Сохранено, Сэр!", Toast.LENGTH_SHORT).show()
}
.setNegativeButton("Отмена", null).show()
}
override fun onDestroy() {
try { tts?.stop(); tts?.shutdown(); speechRecognizer?.destroy() } catch (_: Exception) {}
super.onDestroy()
}
}
