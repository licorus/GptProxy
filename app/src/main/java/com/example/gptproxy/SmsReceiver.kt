package com.example.gptproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SmsMessage

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bundle: Bundle? = intent.extras
        if (bundle != null) {
            try {
                val pdus = bundle["pdus"] as Array<*>
                for (pdu in pdus) {
                    val sms = SmsMessage.createFromPdu(pdu as ByteArray)
                    val sender = sms.originatingAddress ?: ""
                    val message = sms.messageBody

                    // Создаем интент для MainActivity
                    val i = Intent(context, MainActivity::class.java)
                    i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    i.putExtra("sms_sender", sender)
                    i.putExtra("sms_message", message)
                    context.startActivity(i)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
