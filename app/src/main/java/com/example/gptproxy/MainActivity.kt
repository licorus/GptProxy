package com.example.gptproxy

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.*
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
    private lateinit var whitelistListView: ListView
    private lateinit var btnAddWhitelist: Button
    private lateinit var adapter: ArrayAdapter<String>

    private val availableModels = listOf(
        "mistralai/Mistral-Large-Instruct-2411",
        "openai/gpt-oss-20b",
        "openai/gpt-oss-120b",
        "meta-llama/Llama-4-Maverick-17B-128E-Instruct-FP8",
        "Qwen/Qwen3-235B-A22B-Thinking-2507",
        "meta-llama/Llama-3.3-70B-Instruct"
    )

    private var maxTokens: Int = 200
    private var currentModel = "meta-llama/Llama-4-Maverick-17B-128E-Instruct-FP8"
    private var currentSender: String? = null

    // --- Белый список ---
    private val PREFS_NAME = "whitelist_prefs"
    private val KEY_WHITELIST = "whitelist"
    private var whitelist = mutableSetOf<String>()

    private val apiKey = "io-v2-eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJvd25lciI6Ijg4YWI3Mzg2LTdmZDAtNDhmYy1iYjNlLTU3ZjlhNmNlZDI0MiIsImV4cCI6NDkwOTUzNTY0M30.XyxRgO0dL0OcnL3Vz-XnTMXCXNLduVRrk8txgc0qlFsXZy5TlummTd4NDSS_ehen6zwI1lEhUELqhezEEqlq3g"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        .build()

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- UI ---
        scrollView = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL

        logView = TextView(this)
        logView.setPadding(16, 16, 16, 16)

        inputField = EditText(this)
        inputField.hint = "Введите сообщение"

        btnSend = Button(this).apply { text = "Отправить в нейросеть" }
        btnSelectModel = Button(this).apply { text = "Выбрать модель" }
        btnSetTokens = Button(this).apply { text = "Задать max_tokens (сейчас $maxTokens)" }

        val whitelistLabel = TextView(this)
        whitelistLabel.text = "Белый список номеров:"
        whitelistLabel.textSize = 16f

        whitelistListView = ListView(this)

        // --- загружаем белый список ---
        whitelist = loadWhitelist().toMutableSet()
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList(whitelist))
        whitelistListView.adapter = adapter

        btnAddWhitelist = Button(this).apply { text = "Добавить номер" }

        layout.addView(inputField)
        layout.addView(btnSend)
        layout.addView(btnSelectModel)
        layout.addView(btnSetTokens)
        layout.addView(whitelistLabel)
        layout.addView(whitelistListView)
        layout.addView(btnAddWhitelist)
        layout.addView(logView)

        scrollView.addView(layout)
        setContentView(scrollView)

        // --- Разрешения ---
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.INTERNET
            ),
            1
        )

        appendLog("ℹ️ Приложение запущено. Модель: $currentModel, max_tokens=$maxTokens")
        appendLog("✅ Белый список номеров: $whitelist")

        // --- Добавление номера вручную ---
        btnAddWhitelist.setOnClickListener {
            val input = EditText(this)
            input.hint = "+79991234567"

            val builder = AlertDialog.Builder(this)
            builder.setTitle("Добавить номер")
            builder.setView(input)
            builder.setPositiveButton("OK") { _, _ ->
                val number = input.text.toString().trim()
                if (number.isNotEmpty()) {
                    whitelist.add(number)
                    saveWhitelist(whitelist)
                    updateWhitelistUI()
                    appendLog("✅ Номер $number добавлен в белый список")
                }
            }
            builder.setNegativeButton("Отмена", null)
            builder.show()
        }

        // --- Удаление номера (долгий тап) ---
        whitelistListView.setOnItemLongClickListener { _, _, position, _ ->
            val number = adapter.getItem(position) ?: return@setOnItemLongClickListener true
            whitelist.remove(number)
            saveWhitelist(whitelist)
            updateWhitelistUI()
            appendLog("❌ Номер $number удалён из белого списка")
            true
        }

        // --- Кнопка "Отправить" ---
        btnSend.setOnClickListener {
            val text = inputField.text.toString().trim()
            if (text.isNotEmpty()) {
                appendLog("ℹ️ Отправка текста: $text")
                sendToAI(text) { response ->
                    runOnUiThread {
                        appendLog("🤖 Ответ от AI: $response")
                        currentSender?.let { sender ->
                            sendSms(sender, response)
                            appendLog("📤 Ответ по SMS отправлен на $sender")
                        }
                    }
                }
            }
        }

        btnSelectModel.setOnClickListener { showModelSelectionDialog() }
        btnSetTokens.setOnClickListener { showTokenInputDialog() }

        // --- Приём SMS ---
        registerReceiver(smsReceiver, IntentFilter("android.provider.Telephony.SMS_RECEIVED"))

        val smsSender = intent.getStringExtra("sms_sender")
        val smsMessage = intent.getStringExtra("sms_message")

        if (smsSender != null && smsMessage != null) {
            currentSender = smsSender
            handleIncomingSms(smsSender, smsMessage)
        }
    }

    // --- Приём SMS ---
    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val bundle = intent?.extras ?: return
            val pdus = bundle["pdus"] as? Array<*> ?: return
            for (pdu in pdus) {
                val sms = SmsMessage.createFromPdu(pdu as ByteArray)
                val sender = sms.originatingAddress ?: return
                val messageBody = sms.messageBody
                handleIncomingSms(sender, messageBody)
            }
        }
    }

    // --- Обработка SMS ---
    private fun handleIncomingSms(sender: String, messageBody: String) {
        appendLog("📩 Получено SMS от $sender: $messageBody")

        // --- Управление белым списком через SMS ---
        if (messageBody.uppercase().startsWith("WHITELIST")) {
            val parts = messageBody.split(" ")
            when {
                parts.size == 3 && parts[1].equals("ADD", true) -> {
                    val number = parts[2].trim()
                    whitelist.add(number)
                    saveWhitelist(whitelist)
                    updateWhitelistUI()
                    appendLog("✅ Через SMS: добавлен номер $number")
                    sendSms(sender, "✅ Номер $number добавлен в белый список")
                }
                parts.size == 3 && parts[1].equals("REMOVE", true) -> {
                    val number = parts[2].trim()
                    if (whitelist.remove(number)) {
                        saveWhitelist(whitelist)
                        updateWhitelistUI()
                        appendLog("❌ Через SMS: удалён номер $number")
                        sendSms(sender, "❌ Номер $number удалён из белого списка")
                    } else {
                        sendSms(sender, "⚠️ Номер $number не найден в белом списке")
                    }
                }
                parts.size == 2 && parts[1].equals("LIST", true) -> {
                    val list = whitelist.joinToString(", ").ifEmpty { "Белый список пуст" }
                    sendSms(sender, "📋 Белый список: $list")
                }
            }
            return
        }

        // --- Игнорируем чужие SMS ---
        if (!whitelist.contains(sender)) {
            appendLog("🚫 Игнорируем SMS от $sender (не в белом списке)")
            return
        }

        // --- Служебные команды ---
        if (messageBody.startsWith("СМЕНИТЬ")) {
            val parts = messageBody.split(" ")
            if (parts.size == 2) {
                val index = parts[1].toIntOrNull()
                if (index != null && index in availableModels.indices) {
                    currentModel = availableModels[index]
                    appendLog("ℹ️ Модель изменена на: $currentModel")
                } else appendLog("❗️ Ошибка: неправильный номер модели")
            }
            return
        }

        if (messageBody.startsWith("MAX_TOKENS")) {
            val parts = messageBody.split(" ")
            if (parts.size == 2) {
                val value = parts[1].toIntOrNull()
                if (value != null && value > 0) {
                    maxTokens = value
                    appendLog("ℹ️ max_tokens изменён на $maxTokens")
                    runOnUiThread { btnSetTokens.text = "Задать max_tokens (сейчас $maxTokens)" }
                } else appendLog("❗️ Ошибка: некорректное значение max_tokens")
            }
            return
        }

        // --- Отправляем в AI ---
        currentSender = sender
        sendToAI(messageBody) { response ->
            runOnUiThread {
                appendLog("🤖 Ответ: $response")
                sendSms(sender, response)
                appendLog("📤 Ответ отправлен на $sender")
            }
        }
    }

    // --- Обновление списка ---
    private fun updateWhitelistUI() {
        adapter.clear()
        adapter.addAll(ArrayList(whitelist))
        adapter.notifyDataSetChanged()
    }

    // --- Отправка в AI ---
    private fun sendToAI(userMessage: String, callback: (String) -> Unit) {
        val url = "https://api.intelligence.io.solutions/api/v1/chat/completions"

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", "You are a helpful assistant. Please answer concisely."))
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
                runOnUiThread { appendLog("❗️ Ошибка запроса: ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        runOnUiThread { appendLog("❗️ Ошибка ответа: ${response.code}") }
                    } else {
                        val responseBody = response.body?.string()
                        val json = JSONObject(responseBody ?: "{}")
                        val choices = json.optJSONArray("choices")
                        val msg = choices?.optJSONObject(0)?.optJSONObject("message")
                        val content = msg?.optString("content") ?: "Нет ответа"
                        callback(content.trim())
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

    // --- Диалоги ---
    private fun showModelSelectionDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Выберите модель")
        builder.setItems(availableModels.toTypedArray()) { _, which ->
            currentModel = availableModels[which]
            appendLog("ℹ️ Модель изменена на: $currentModel")
        }
        builder.show()
    }

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
                appendLog("ℹ️ max_tokens изменён на $maxTokens")
            } else appendLog("❗️ Ошибка: некорректное значение max_tokens")
        }
        builder.setNegativeButton("Отмена", null)
        builder.show()
    }

    // --- Лог ---
    private fun appendLog(text: String) {
        logView.append("$text\n\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // --- SharedPreferences через JSON ---
    private fun saveWhitelist(set: Set<String>) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (num in set) jsonArray.put(num)
        prefs.edit().putString(KEY_WHITELIST, jsonArray.toString()).apply()
    }

    private fun loadWhitelist(): MutableSet<String> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        return try {
            val value = prefs.getString(KEY_WHITELIST, null) ?: return mutableSetOf()
            val jsonArray = JSONArray(value)
            val result = mutableSetOf<String>()
            for (i in 0 until jsonArray.length()) {
                result.add(jsonArray.optString(i))
            }
            result
        } catch (e: Exception) {
            appendLog("⚠️ Ошибка чтения белого списка: ${e.message}")
            mutableSetOf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(smsReceiver)
    }
}