package com.termuxbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.termuxbridge.MainActivity.addLog
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import org.json.JSONObject
import java.io.IOException
import java.net.ServerSocket
import java.util.*

class HttpServer(private val port: Int, private val onLog: (String) -> Unit) {

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder().build()

    // TTS instance
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Notification channels
    private val notificationManager: NotificationManager by lazy {
        MainActivity.addLog("Context: ${android.app.ActivityThread.currentApplication().applicationContext}")
        val nm = ContextCompat.getSystemService<NotificationManager>(
            android.app.ActivityThread.currentApplication().applicationContext,
            NotificationManager::class.java
        )!!
        createNotificationChannels(nm)
        nm
    }

    fun start() {
        serverSocket = ServerSocket(port)
        MainActivity.addLog("Server listening on 127.0.0.1:$port")

        // Init TTS
        tts = TextToSpeech(
            android.app.ActivityThread.currentApplication().applicationContext
        ) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                addLog("TTS engine ready")
            } else {
                addLog("TTS init failed")
            }
        }

        scope.launch {
            while (true) {
                try {
                    val socket = serverSocket!!.accept()
                    scope.launch { handleRequest(socket) }
                } catch (e: Exception) {
                    if (e.message != "Socket closed") {
                        onLog("ERROR: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    fun stop() {
        serverSocket?.close()
        serverSocket = null
        tts?.stop()
        tts?.shutdown()
        ttsReady = false
    }

    private suspend fun handleRequest(socket: java.net.Socket) {
        try {
            val reader = socket.getInputStream().bufferedReader()
            val request = reader.readLine() ?: return
            val method = request.split(" ")[0]
            val path = request.split(" ")[1]

            onLog("$method $path")

            // Parse headers
            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            var line: String?

            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val colon = line!!.indexOf(':')
                if (colon > 0) {
                    val name = line!!.substring(0, colon).trim()
                    val value = line!!.substring(colon + 1).trim()
                    headers[name.lowercase()] = value
                    if (name.lowercase() == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }

            // Read body if present
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                val read = reader.read(buf)
                String(buf, 0, read)
            } else ""

            val response = when {
                path.startsWith("/sms/send") -> handleSmsSend(body)
                path.startsWith("/notification") -> handleNotification(body)
                path.startsWith("/tts") -> handleTts(body)
                path.startsWith("/clipboard") -> handleClipboard(body)
                path.startsWith("/location") -> handleLocation(body)
                path.startsWith("/health") -> jsonResponse(JSONObject().put("status", "ok").put("port", port).toString())
                path.startsWith("/") -> jsonResponse(JSONObject().put("service", "TermuxBridge").put("version", "0.1.0").toString())
                else -> notFoundResponse()
            }

            socket.outputStream.write(response.toByteArray())
            socket.close()
        } catch (e: Exception) {
            onLog("ERROR handling request: ${e.message}")
        }
    }

    private fun handleSmsSend(body: String): String {
        return try {
            // Check SMS permission first
            val ctx = android.app.ActivityThread.currentApplication().applicationContext
            val hasPermission = ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.SEND_SMS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                return jsonResponse(
                    JSONObject().put("error", "SEND_SMS permission not granted").toString(),
                    403
                )
            }

            val json = JSONObject(body)
            val to = json.optString("to", "")
            val message = json.optString("message", "")

            if (to.isEmpty() || message.isEmpty()) {
                return jsonResponse(JSONObject().put("error", "missing to or message").toString(), 400)
            }

            val smsManager = ctx.getSystemService(SmsManager::class.java)
                ?: return jsonResponse(JSONObject().put("error", "Device does not support SMS").toString(), 500)

            // Handle multi-part SMS
            val parts = smsManager.divideMessage(message)
            if (parts.isEmpty()) {
                return jsonResponse(JSONObject().put("error", "message is empty").toString(), 400)
            }

            for (part in parts) {
                smsManager.sendTextMessage(to, null, part, null, null)
            }

            onLog("SMS sent to $to (${parts.size} part${if (parts.size > 1) "s" else ""})")
            jsonResponse(JSONObject().put("success", true).put("to", to).put("parts", parts.size).toString())
        } catch (e: SecurityException) {
            onLog("SMS ERROR: permission denied")
            jsonResponse(JSONObject().put("error", "SEND_SMS permission denied").toString(), 403)
        } catch (e: Exception) {
            onLog("SMS ERROR: ${e.message}")
            jsonResponse(JSONObject().put("error", e.message).toString(), 500)
        }
    }

    private fun handleNotification(body: String): String {
        return try {
            if (body.isEmpty()) {
                return jsonResponse(JSONObject().put("error", "missing notification body").toString(), 400)
            }

            val json = JSONObject(body)
            val title = json.optString("title", "Alert")
            val bodyText = json.optString("body", "")
            val priority = json.optInt("priority", 0)
            val channel = json.optString("channel", "alerts")

            // Validate channel
            val validChannels = setOf("alerts", "info", "silent")
            val safeChannel = if (channel in validChannels) channel else "info"

            val ctx = android.app.ActivityThread.currentApplication().applicationContext

            // Check notification permission on Android 13+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    ctx, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasPermission) {
                    return jsonResponse(JSONObject().put("error", "POST_NOTIFICATIONS permission not granted").toString(), 403)
                }
            }

            val notification = NotificationCompat.Builder(ctx, safeChannel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(bodyText)
                .setPriority(priority)
                .setAutoCancel(true)
                .build()

            val nm = ContextCompat.getSystemService<NotificationManager>(ctx, NotificationManager::class.java)!!
            nm.notify(System.currentTimeMillis().toInt(), notification)

            onLog("Notification: $title [$safeChannel]")
            jsonResponse(JSONObject().put("success", true).put("channel", safeChannel).toString())
        } catch (e: SecurityException) {
            onLog("Notification ERROR: permission denied")
            jsonResponse(JSONObject().put("error", "POST_NOTIFICATIONS permission denied").toString(), 403)
        } catch (e: Exception) {
            onLog("Notification ERROR: ${e.message}")
            jsonResponse(JSONObject().put("error", e.message).toString(), 500)
        }
    }

    private fun handleTts(body: String): String {
        return try {
            if (!ttsReady) {
                return jsonResponse(JSONObject().put("error", "TTS not ready").toString(), 503)
            }

            if (body.isEmpty()) {
                return jsonResponse(JSONObject().put("error", "missing text").toString(), 400)
            }

            val json = JSONObject(body)
            val text = json.optString("text", "")
            val lang = json.optString("lang", "pt-PT")
            val rate = json.optDouble("rate", 1.0)

            if (text.isEmpty()) {
                return jsonResponse(JSONObject().put("error", "missing text").toString(), 400)
            }

            val langResult = tts?.setLanguage(Locale.forLanguageTag(lang))
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                return jsonResponse(JSONObject().put("error", "language '$lang' not supported").toString(), 400)
            }

            tts?.setSpeechRate(rate.toFloat())
            val utteranceId = "utterance_${System.currentTimeMillis()}"
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

            onLog("TTS: [$lang] $text")
            jsonResponse(JSONObject().put("success", true)
                .put("text", text)
                .put("lang", lang)
                .put("rate", rate)
                .toString())
        } catch (e: Exception) {
            onLog("TTS ERROR: ${e.message}")
            jsonResponse(JSONObject().put("error", e.message).toString(), 500)
        }
    }

    private fun handleClipboard(body: String): String {
        return try {
            val ctx = android.app.ActivityThread.currentApplication().applicationContext
            val clipboard = ctx.getSystemService(android.content.ClipboardManager::class.java)

            if (body.isEmpty()) {
                // GET clipboard
                val text = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                jsonResponse(JSONObject().put("content", text).toString())
            } else {
                // SET clipboard
                val json = JSONObject(body)
                val content = json.optString("content", "")
                val clip = android.content.ClipData.newPlainText("TermuxBridge", content)
                clipboard?.setPrimaryClip(clip)
                jsonResponse(JSONObject().put("success", true).toString())
            }
        } catch (e: Exception) {
            onLog("Clipboard ERROR: ${e.message}")
            jsonResponse(JSONObject().put("error", e.message).toString(), 500)
        }
    }

    private fun handleLocation(body: String): String {
        return try {
            // Simplified - real implementation needs FusedLocationProviderClient
            jsonResponse(JSONObject().put("error", "not implemented").toString(), 501)
        } catch (e: Exception) {
            jsonResponse(JSONObject().put("error", e.message).toString(), 500)
        }
    }

    private fun createNotificationChannels(nm: NotificationManager) {
        val channels = listOf(
            NotificationChannel("alerts", "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "High priority alerts"
            },
            NotificationChannel("info", "Info", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Informational notifications"
            },
            NotificationChannel("silent", "Silent", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Low priority notifications"
            }
        )
        channels.forEach { nm.createNotificationChannel(it) }
    }

    private fun jsonResponse(body: String, statusCode: Int = 200): String {
        return """
            |HTTP/1.1 $statusCode OK
            |Content-Type: application/json
            |Content-Length: ${body.toByteArray().size}
            |Connection: close
            |
            |$body
        """.trimMargin()
    }

    private fun notFoundResponse(): String {
        return jsonResponse(JSONObject().put("error", "not found").toString(), 404)
    }
}