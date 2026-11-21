package com.aylin.assistant

import android.Manifest
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
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
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())
    private var isAwake = false
    private var isSpeaking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1117"))
            setPadding(48, 80, 48, 48)
        }

        val tvTitle = TextView(this).apply {
            text = "АЙЛИН"
            setTextColor(Color.parseColor("#58A6FF"))
            textSize = 34f
            gravity = Gravity.CENTER
        }

        tvStatus = TextView(this).apply {
            text = "🎙 Жду слово «Айлин»..."
            setTextColor(Color.parseColor("#3FB950"))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 24)
        }

        tvLog = TextView(this).apply {
            text = "Режим Джарвис активен. Просто скажите «Айлин», Сэр..."
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(24, 24, 24, 24)
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#161B22"))
            addView(tvLog)
        }

        val btnSettings = Button(this).apply {
            text = "⚙️ Настройки API и WhatsApp"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#30363D"))
            textSize = 15f
            setOnClickListener { showSettingsDialog() }
        }

        root.addView(tvTitle)
        root.addView(tvStatus)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(btnSettings, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 130).apply { topMargin = 20 })

        setContentView(root)

        checkPermissions()
        tts = TextToSpeech(this, this)
        setupSpeechRecognizer()
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
                    override fun onStart(utteranceId: String?) { isSpeaking = true }
                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        runOnUiThread { restartListening(250) }
                    }
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        runOnUiThread { restartListening(250) }
                    }
                })
                isAwake = true
                speak("Да, Сэр.")
            } else {
                restartListening(400)
            }
        } catch (_: Exception) {}
    }

    private fun speak(text: String) {
        isSpeaking = true
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        tvLog.text = "Айлин: $text\n\n${tvLog.text}"
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AYLIN_SPEECH")
        } catch (_: Exception) {
            isSpeaking = false
            restartListening(300)
        }
    }

    private fun setupSpeechRecognizer() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) return
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    tvStatus.text = if (isAwake) "🎙 Слушаю команду, Сэр..." else "👂 Жду слово «Айлин»..."
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (!isSpeaking) restartListening(300)
                }
                override fun onResults(results: Bundle?) {
                    val command = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (command.isNullOrBlank()) {
                        if (!isSpeaking) restartListening(200)
                        return
                    }
                    processVoiceInput(command)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        } catch (_: Exception) {}
    }

    private fun restartListening(delayMs: Long = 250) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!isSpeaking && !isFinishing) {
                try {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
                    }
                    speechRecognizer?.startListening(intent)
                } catch (_: Exception) {
                    restartListening(800)
                }
            }
        }, delayMs)
    }

    private fun processVoiceInput(raw: String) {
        val lower = raw.lowercase(Locale.getDefault()).trim()
        val wakeWords = listOf("айлин", "айлен", "алин", "айли", "aylin")
        val hasWakeWord = wakeWords.any { lower.contains(it) }

        if (!isAwake && !hasWakeWord) {
            restartListening(200)
            return
        }

        tvLog.text = "Сэр: $raw\n${tvLog.text}"

        var cleanCmd = lower
        for (w in wakeWords) cleanCmd = cleanCmd.replace(w, "")
        cleanCmd = cleanCmd.trim()

        if (cleanCmd.isEmpty()) {
            isAwake = true
            speak("Да, Сэр?")
            return
        }

        isAwake = false
        executeCommand(cleanCmd, raw)
    }

    private fun executeCommand(lower: String, originalText: String) {
        when {
            (lower.contains("ватсап") || lower.contains("whatsapp")) && !lower.contains("отправ") && !lower.contains("напиш") -> {
                speak("Так точно, Сэр!")
                launchApp("com.whatsapp")
            }
            lower.contains("ютуб") || lower.contains("youtube") -> {
                val query = lower.replace("открой", "").replace("открывай", "").replace("включи", "")
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
try { startActivity(Intent(Intent.ACTION_VIEW).apply { type = "image/*"; flags = Intent.FLAG_ACTIVITY_NEW_TASK }) } catch (: Exception) {}
}
lower.contains("музык") -> {
speak("Так точно, Сэр!")
try { startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)) } catch (: Exception) {}
}
lower.contains("метатрейдер") || lower.contains("mt5") || lower.contains("график") -> {
speak("Так точно, Сэр! Открываю MetaTrader 5.")
launchApp("net.metaquotes.metatrader5")
}
else -> askGemini(originalText)
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
if (apiKey.isEmpty()) { speak("Сэр, укажите Gemini API ключ в настройках внизу."); return }
tvStatus.text = "🧠 Айлин думает..."
scope.launch(Dispatchers.IO) {
try {
val host = "googleapis.com"
val endpoint = "https://$host/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
val prompt = "Ты Айлин, личный голосовой ассистент. Обращайся к пользователю исключительно Сэр. Отвечай кратко и чётко. Если Сэр просит отправить WhatsApp, верни ТОЛЬКО JSON с полями whatsapp, phone, text. Если Сэр спрашивает про трейдинг, XAUUSD, SMC, ICT или FVG, дай краткий точный анализ."
val bodyJson = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "$prompt\n\nЗапрос Сэра: $userText")))))
val req = Request.Builder().url(endpoint).post(bodyJson.toString().toRequestBody("application/json".toMediaType())).build()
val respStr = client.newCall(req).execute().body?.string() ?: ""
val jsonResp = JSONObject(respStr)
if (jsonResp.has("error")) {
val errMsg = jsonResp.getJSONObject("error").optString("message", "Ошибка API")
withContext(Dispatchers.Main) {
tvLog.text = "Ошибка API: $errMsg\n\n${tvLog.text}"
speak("Сэр, проверьте ключ API в настройках.")
}
return@launch
}
val reply = jsonResp.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
withContext(Dispatchers.Main) {
if (reply.contains("whatsapp") && reply.contains("{")) {
val obj = JSONObject("{${reply.substringAfter("{").substringBeforeLast("}")}}")
sendWhatsApp(greenUrl, obj.getString("phone"), obj.getString("text"))
} else speak(reply)
}
} catch (e: Exception) {
withContext(Dispatchers.Main) {
tvLog.text = "Сеть: ${e.message}\n\n${tvLog.text}"
speak("Сэр, проверьте интернет или ключ API.")
}
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
override fun onResume() {
super.onResume()
if (!isSpeaking) restartListening(300)
}
override fun onDestroy() {
handler.removeCallbacksAndMessages(null)
try { tts?.stop(); tts?.shutdown(); speechRecognizer?.destroy() } catch (_: Exception) {}
super.onDestroy()
}
}
