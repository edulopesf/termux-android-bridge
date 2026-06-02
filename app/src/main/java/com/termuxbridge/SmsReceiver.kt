package com.termuxbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

/**
 * BroadcastReceiver that intercepts incoming SMS and forwards
 * the content to the Termux webhook endpoint via HTTP POST.
 *
 * Architecture:
 *   SMS arrives → SMS_DELIVER (Android 14+) or SMS_RECEIVED → this receiver
 *             → parse SmsMessage(s)
 *             → HTTP POST http://127.0.0.1:9000/webhook/sms
 *             → Termux Python webhook processes it
 *
 * NOTE: Must be the default SMS app on Android 14+ to receive SMS_DELIVER.
 */
class SmsReceiver : BroadcastReceiver() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Termux webhook receiver port (separate from Android HTTP server port 8080)
    companion object {
        const val TERMUX_WEBHOOK_HOST = "127.0.0.1"
        const val TERMUX_WEBHOOK_PORT = 9000
        const val TERMUX_WEBHOOK_PATH = "/webhook/sms"
        private const val TAG = "SmsReceiver"
        private const val DEVICE_NAME = "TermuxBridge/0.1.0"

        fun getWebhookUrl(): String =
            "http://$TERMUX_WEBHOOK_HOST:$TERMUX_WEBHOOK_PORT$TERMUX_WEBHOOK_PATH"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Only handle SMS intents
        val smsDeliverAction = Telephony.Sms.Intents.SMS_DELIVER_ACTION
        val smsReceivedAction = "android.provider.Telephony.SMS_RECEIVED"

        if (intent.action != smsDeliverAction && intent.action != smsReceivedAction) {
            return
        }

        // keepReceiverAlive lets the coroutine run after onReceive returns
        val pendingResult = goAsync()

        scope.launch {
            try {
                handleSms(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error handling SMS: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleSms(context: Context, intent: Intent) {
        try {
            // Parse all SMS parts from the intent
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            if (messages.isEmpty()) {
                Log.w(TAG, "No messages in SMS intent")
                return
            }

            // Extract metadata — use first message for sender/timestamp
            val from = messages[0].originatingAddress ?: "unknown"
            val timestamp = messages[0].timestampMillis
            val parts = messages.size

            // Concatenate multipart body (join in order)
            val body = messages.joinToString("") { it.messageBody ?: "" }

            if (body.isEmpty()) {
                Log.w(TAG, "Empty SMS body from $from")
                return
            }

            Log.d(TAG, "SMS from $from: $body (${parts}part${if (parts > 1) "s" else ""})")

            // Build JSON payload
            val payload = JSONObject().apply {
                put("from", from)
                put("body", body)
                put("timestamp", timestamp)
                put("device", DEVICE_NAME)
                put("parts", parts)
            }

            // Forward to Termux webhook
            val forwarded = forwardToTermux(payload)

            if (forwarded) {
                Log.d(TAG, "SMS forwarded to Termux webhook")
            } else {
                Log.w(TAG, "Termux webhook unavailable — SMS not forwarded")
            }

        } catch (e: SecurityException) {
            // Not the default SMS app — SMS_DELIVER not available
            Log.e(TAG, "SecurityException: not default SMS app? ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling SMS: ${e.message}")
        }
    }

    private suspend fun forwardToTermux(payload: JSONObject): Boolean {
        return try {
            val request = Request.Builder()
                .url(getWebhookUrl())
                .post(RequestBody.create(
                    MediaType.parse("application/json"),
                    payload.toString()
                ))
                .header("User-Agent", DEVICE_NAME)
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: IOException) {
            // Termux not reachable or webhook down — fail silently
            Log.w(TAG, "Termux webhook unreachable: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Webhook error: ${e.message}")
            false
        }
    }
}