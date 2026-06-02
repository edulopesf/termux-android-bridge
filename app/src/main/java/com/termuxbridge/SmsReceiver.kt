package com.termuxbridge

import android.app.Activity
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
 * BroadcastReceiver that intercepts incoming SMS and forwards them
 * to the Termux webhook receiver via HTTP POST.
 *
 * On Android 14+ (UPSIDE_DOWN_CAKE), requires the app to be the
 * default SMS app to receive SMS_DELIVER broadcasts.
 *
 * Reference:
 * - https://developer.android.com/reference/android/content/BroadcastReceiver
 * - https://developer.android.com/reference/android/provider/Telephony.Sms.Intents
 */
class SmsReceiver : BroadcastReceiver() {

    private val client = OkHttpClient.Builder().build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Termux webhook URL — port must match the Termux side sms-listend server
    private val termuxWebhookUrl = "http://127.0.0.1:9000/webhook/sms"

    companion object {
        private const val TAG = "SmsReceiver"

        // Whether to run in simulation mode (no actual HTTP post, just log)
        // Set to false in production once tested
        var simulationMode = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Check if this is an SMS intent
        val isSmsDeliver = intent.action == Telephony.Sms.Intents.SMS_DELIVER_ACTION
        val isSmsReceived = intent.action == "android.provider.Telephony.SMS_RECEIVED"

        if (!isSmsDeliver && !isSmsReceived) {
            return
        }

        // Keep BroadcastReceiver alive while coroutine runs
        val pendingResult = goAsync()

        scope.launch {
            try {
                handleSms(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling SMS: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleSms(context: Context, intent: Intent) {
        try {
            // Parse all message parts from the intent
            // On Android 14+, getMessagesFromIntent handles both SMS_DELIVER and SMS_RECEIVED
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isEmpty()) {
                Log.w(TAG, "No SMS messages in intent")
                return
            }

            // Extract sender, timestamp, and body
            // For multipart SMS, concatenate all parts in order
            val from = messages[0].originatingAddress ?: "unknown"
            val timestamp = messages[0].timestampMillis
            val body = messages.joinToString("") { it.messageBody ?: "" }
            val partsCount = messages.size

            Log.i(TAG, "SMS received from $from: ${body.take(50)}${if (body.length > 50) "..." else ""} ($partsCount part${if (partsCount > 1) "s" else ""})")

            // Build JSON payload
            val payload = JSONObject().apply {
                put("from", from)
                put("body", body)
                put("timestamp", timestamp)
                put("device", "TermuxBridge/0.1.0")
                put("parts", partsCount)
            }

            if (simulationMode) {
                // Simulation mode: just log, don't send to Termux
                Log.i(TAG, "[SIMULATION] Would POST to $termuxWebhookUrl: $payload")
                return
            }

            // Forward to Termux webhook via HTTP POST
            val requestBody = RequestBody.create(
                MediaType.parse("application/json"),
                payload.toString()
            )

            val request = Request.Builder()
                .url(termuxWebhookUrl)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "SMS forwarded to Termux webhook (${response.code()})")
                } else {
                    Log.w(TAG, "Termux webhook returned ${response.code()}: ${response.message()}")
                }
            }
        } catch (e: IOException) {
            // Termux webhook not reachable — this is not critical, just log
            Log.w(TAG, "Termux not reachable: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error forwarding SMS: ${e.message}")
        }
    }
}