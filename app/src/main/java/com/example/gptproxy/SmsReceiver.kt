package com.example.gptproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            val bundle: Bundle? = intent.extras
            if (bundle != null) {
                try {
                    val pdus = bundle["pdus"] as Array<*>
                    for (pdu in pdus) {
                        val sms = SmsMessage.createFromPdu(pdu as ByteArray)
                        val sender = sms.originatingAddress
                        val messageBody = sms.messageBody

                        Log.d("SmsReceiver", "📩 Получено SMS от $sender: $messageBody")

                        // Запускаем MainActivity с данными SMS
                        val activityIntent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra("sms_sender", sender)
                            putExtra("sms_message", messageBody)
                        }
                        context.startActivity(activityIntent)
                    }
                } catch (e: Exception) {
                    Log.e("SmsReceiver", "❌ Ошибка обработки SMS", e)
                }
            }
        }
    }
}
