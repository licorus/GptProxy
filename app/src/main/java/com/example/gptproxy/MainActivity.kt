package com.example.gptproxy

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.telephony.SmsMessage
import android.telephony.SmsManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var btnSend: Button
    private lateinit var btnSelectModel: Button
    private lateinit var btnSetTokens: Button

    private val availableModels = listOf(
        "mistralai/Mistral-Large-Instruct-2411",
        "openai/gpt-oss-20b",
        "openai/gpt-oss-120b",
        "meta-llama/Llama-4-Maverick-17B-128E-Instruct-FP8",
        "Qwen/Qwen3-235B-A22B-Thinking-2507",
        "meta-llama/Llama-3.3-70B-Instruct"
    )

    private var maxTokens: Int = 200 // значение по умолчанию

    // Текущая выбранная модель
    private var currentModel = "openai/gpt-oss-120b"

    // Текущий отправитель SMS (чтобы логировать)
    private var currentSender: String? = null

    // API Key (замени на свой)
    private val apiKey = "io-v2-eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJvd25lciI6Ijg4YWI3Mzg2LTdmZDAtNDhmYy1iYjNlLTU3ZjlhNmNlZDI0MiIsImV4cCI6NDkwOTUzNTY0M30.XyxRgO0dL0OcnL3Vz-XnTMXCXNLduVRrk8txgc0qlFsXZy5TlummTd4NDSS_ehen6zwI1lEhUELqhezEEqlq3g"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // увеличить таймаут
        .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS)) // чтобы не висло на повторных вызовах
        .build()

    //@SuppressLint("SetJavaScriptEnabled")
    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // UI
        scrollView = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL

        logView = TextView(this)
        logView.setPadding(16, 16, 16, 16)

        inputField = EditText(this)
        inputField.hint = "Введите сообщение"

        btnSend = Button(this)
        btnSend.text = "Отправить в нейросеть"

        btnSelectModel = Button(this)
        btnSelectModel.text = "Выбрать модель"

        btnSetTokens = Button(this)
        btnSetTokens.text = "Задать max_tokens (сейчас $maxTokens)"

        layout.addView(inputField)
        layout.addView(btnSend)
        layout.addView(btnSelectModel)
        layout.addView(btnSetTokens)
        layout.addView(logView)

        scrollView.addView(layout)
        setContentView(scrollView)

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.INTERNET
            ),
            1
        )

        appendLog("ℹ\uFE0F Приложение запущено. Текущая модель: $currentModel, max_tokens=$maxTokens")

        // Кнопка отправки
        btnSend.setOnClickListener {
            val text = inputField.text.toString().trim()
            if (text.isNotEmpty()) {
                appendLog("ℹ\uFE0F Отправка текста: $text")
                sendToAI(text) { response ->
                    runOnUiThread {
                        appendLog("\uD83E\uDD16 Ответ от AI: $response")
                        currentSender?.let { sender ->
                            sendSms(sender, response)
                            appendLog("\uD83D\uDCE8 Отправлен ответ по SMS на $sender")
                        }
                    }
                }
            }
        }

        // Кнопка выбора модели
        btnSelectModel.setOnClickListener {
            showModelSelectionDialog()
        }

        // Кнопка смены max_tokens вручную
        btnSetTokens.setOnClickListener {
            showTokenInputDialog()
        }

        // Регистрируем приём SMS
        registerReceiver(smsReceiver, IntentFilter("android.provider.Telephony.SMS_RECEIVED"))
    }

    // --- Приём SMS ---
    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val bundle = intent?.extras
            if (bundle != null) {
                val pdus = bundle["pdus"] as Array<*>
                for (pdu in pdus) {
                    val sms = SmsMessage.createFromPdu(pdu as ByteArray)
                    currentSender = sms.originatingAddress
                    val messageBody = sms.messageBody
                    appendLog("📩 Получено SMS от $currentSender: $messageBody")

                    // --- Команда смены модели ---
                    if (messageBody.startsWith("СМЕНИТЬ")) {
                        val parts = messageBody.split(" ")
                        if (parts.size == 2) {
                            val index = parts[1].toIntOrNull()
                            if (index != null && index in availableModels.indices) {
                                currentModel = availableModels[index]
                                appendLog("ℹ️ Модель изменена на: $currentModel")
                            } else {
                                appendLog("❗️ Ошибка: неправильный номер модели")
                            }
                        }
                        return
                    }

                    // --- Команда смены max_tokens ---
                    if (messageBody.startsWith("MAX_TOKENS")) {
                        val parts = messageBody.split(" ")
                        if (parts.size == 2) {
                            val value = parts[1].toIntOrNull()
                            if (value != null && value > 0) {
                                maxTokens = value
                                appendLog("ℹ️ max_tokens изменён на $maxTokens")
                                runOnUiThread {
                                    btnSetTokens.text = "Задать max_tokens (сейчас $maxTokens)"
                                }
                            } else {
                                appendLog("❗️ Ошибка: некорректное значение max_tokens")
                            }
                        }
                        return
                    }

                    // --- Если это обычный вопрос ---
                    sendToAI(messageBody) { response ->
                        runOnUiThread {
                            appendLog("🤖 Ответ на SMS от $currentSender:\n$response")
                            currentSender?.let { sender ->
                                sendSms(sender, response)
                                appendLog("📤 Отправлен ответ по SMS на $sender")
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Отправка в AI ---
    private fun sendToAI(userMessage: String, callback: (String) -> Unit) {
        val url = "https://api.intelligence.io.solutions/api/v1/chat/completions"

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system")
            .put("content", "You are a helpful assistant. Please answer concisely, but still informative."))
        messages.put(JSONObject().put("role", "user").put("content", userMessage))

        val bodyJson = JSONObject()
        bodyJson.put("model", currentModel)
        bodyJson.put("messages", messages)
        bodyJson.put("max_tokens", maxTokens)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create("application/json".toMediaType(), bodyJson.toString()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { appendLog("❗\uFE0F Ошибка запроса: ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        runOnUiThread { appendLog("❗\uFE0F Ошибка ответа: ${response.code}") }
                    } else {
                        val responseBody = response.body?.string()
                        val json = JSONObject(responseBody ?: "{}")
                        val choices = json.optJSONArray("choices")
                        val msg = choices?.optJSONObject(0)?.optJSONObject("message")
                        var content = msg?.optString("content") ?: "Нет ответа"

                        if (currentModel.startsWith("DeepSeek", ignoreCase = true)) {
                            // Убираем все блоки <think>...</think>
                            content = content.replace(
                                Regex("<think>[\\s\\S]*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                                ""
                            )
                        }

                        content = content.trim()

                        callback(content)
                    }
                }
            }
        })
    }


    // --- Отправка SMS ---
    private fun sendSms(phone: String, message: String) {
        val smsManager = SmsManager.getDefault()
        val parts = smsManager.divideMessage(message)
        smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
    }

    // --- Диалог выбора модели ---
    private fun showModelSelectionDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Выберите модель")
        builder.setItems(availableModels.toTypedArray()) { _, which ->
            currentModel = availableModels[which]
            appendLog("ℹ\uFE0F Модель изменена на: $currentModel")
        }
        builder.show()
    }

    // --- Диалог для ввода max_tokens ---
    private fun showTokenInputDialog() {
        val input = EditText(this)
        input.hint = "Введите max_tokens (число)"
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Задать max_tokens")
        builder.setView(input)
        builder.setPositiveButton("OK") { _, _ ->
            val value = input.text.toString().toIntOrNull()
            if (value != null && value > 0) {
                maxTokens = value
                btnSetTokens.text = "Задать max_tokens (сейчас $maxTokens)"
                appendLog("ℹ\uFE0F max_tokens изменён на $maxTokens")
            } else {
                appendLog("❗\uFE0F Ошибка: некорректное значение max_tokens")
            }
        }
        builder.setNegativeButton("Отмена", null)
        builder.show()
    }

    // --- Лог ---
    private fun appendLog(text: String) {
        logView.append("$text\n\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(smsReceiver)
    }
}