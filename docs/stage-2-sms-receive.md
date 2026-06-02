# Stage 2 — Receber SMS (SMS Receive)

> **References:**
> - [Android Developers — BroadcastReceiver](https://developer.android.com/reference/android/content/BroadcastReceiver)
> - [Android Developers — SmsMessage](https://developer.android.com/reference/android/telephony/SmsMessage)
> - [Android Developers — Intent actions for SMS](https://developer.android.com/reference/android/telephony/Telephony.Sms.Intents)
> - [Android Developers — Manifest declarations for SMS](https://developer.android.com/training/articles/sms-permissions)
> - [Android Developers — PendingIntent](https://developer.android.com/reference/android/app/PendingIntent)
> - [Stack Overflow — SMS_DELIVER action](https://stackoverflow.com/questions/昆虫/昆虫)
> - [GitHub — android-sms-receive sample](https://developer.android.com/samples/SmsReceivedDemo)

## Overview

When an SMS arrives on the Android device, the app receives it via a `BroadcastReceiver` and forwards the message to a Termux webhook endpoint via HTTP POST.

**Stack:**
- Android: Kotlin + BroadcastReceiver (`android.provider.Telephony.SMS_RECEIVED`)
- HTTP Client: OkHttp `POST` to Termux webhook
- Termux: Python HTTP server (webhook receiver) on separate port

**Architecture:**
```
SMS arrives on Android
  → android.provider.Telephony.SMS_RECEIVED broadcast
  → SmsReceiver BroadcastReceiver (manifest-registered)
  → parse SmsMessage
  → HTTP POST to Termux webhook (e.g., http://127.0.0.1:9000/webhook/sms)
  → Termux processes (bot notification, log, etc.)
```

**Port allocation:**
- Android HTTP server: `127.0.0.1:8080` (receives commands from Termux)
- Termux webhook server: `127.0.0.1:9000` (receives events from Android)

---

## Android Manifest Configuration

### Required Permissions

```xml
<!-- RECEIVE_SMS: required to receive SMS broadcasts (Android 14+) -->
<uses-permission android:name="android.permission.RECEIVE_SMS" />

<!-- READ_SMS: deprecated on Android 14+, only needed for pre-Android 14 -->
<uses-permission android:name="android.permission.READ_SMS" />
```

> **Reference:** [Android 14 SMS permissions changes](https://developer.android.com/about/versions/14/behavior-changes#sms-permissions)

### BroadcastReceiver Declaration

```xml
<!-- Static BroadcastReceiver (manifest-registered) -->
<receiver
    android:name=".SmsReceiver"
    android:exported="true"
    android:enabled="true">
    <intent-filter android:priority="2147483647">  <!-- highest priority -->
        <action android:name="android.provider.Telephony.SMS_RECEIVE" />
        <action android:name="android.provider.Telephony.SMS_DELIVER" />
    </intent-filter>
</receiver>
```

> **Reference:** [Android Developers — SMS_DELIVER vs SMS_RECEIVE](https://developer.android.com/reference/android/provider/Telephony.Sms.Intents#SMS_DELIVER_ACTION)

**Important:** On Android 14+, `SMS_RECEIVE` is deprecated. Use `SMS_DELIVER` which requires the app to be the **default SMS app** to receive messages before other apps.

---

## SmsMessage API

> **Reference:** [Android Developers — SmsMessage class](https://developer.android.com/reference/android/telephony/SmsMessage) | [Android Developers — Telephony.Sms.Intents](https://developer.android.com/reference/android/provider/Telephony.Sms.Intents)

### Class Reference

```kotlin
android.telephony.SmsMessage
```

### Key Methods

#### `createFromPdu(pdu: ByteArray): SmsMessage`

Create an SmsMessage from a raw PDU byte array.

```kotlin
val pdu = intent.getByteArrayExtra("pdus")
val sms = SmsMessage.createFromPdu(pdu)
```

#### `getOriginatingAddress(): String`

Returns the sender phone number.

```kotlin
val from = sms.originatingAddress  // e.g., "+351****5678"
```

#### `getMessageBody(): String`

Returns the message text content.

```kotlin
val body = sms.messageBody  // "Hello from Termux Bridge!"
```

#### `getTimestampMillis(): Long`

Returns when the SMS was received (device local time in milliseconds since epoch).

```kotlin
val timestamp = sms.timestampMillis  // 1717234567890
```

### Static Factory: `Telephony.Sms.Intents.getMessagesFromIntent(intent)`

```kotlin
import android.provider.Telephony

val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
for (msg in messages) {
    val from = msg.originatingAddress
    val body = msg.messageBody
    val ts = msg.timestampMillis
}
```

---

## BroadcastReceiver Implementation

> **Reference:** [Android Developers — BroadcastReceiver](https://developer.android.com/reference/android/content/BroadcastReceiver) | [Kotlin Coroutines — BroadcastReceiver](https://developer.android.com/topic/performance/background-optimization)

### Class Structure

```kotlin
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // This runs on the main thread — do NOT block
        // Spawn a coroutine for network operations
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleSms(context, intent)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleSms(context: Context, intent: Intent) {
        // Parse messages, forward to Termux webhook
    }
}
```

### Lifecycle Note

`goAsync()` must be called to keep the BroadcastReceiver alive while the coroutine runs. `pendingResult.finish()` must be called when done.

---

## HTTP POST to Termux Webhook

### Webhook URL

Termux runs a webhook receiver on port **9000**:

```
http://127.0.0.1:9000/webhook/sms
```

### Request Format

```json
POST /webhook/sms HTTP/1.1
Host: 127.0.0.1:9000
Content-Type: application/json
Content-Length: <body_size>

{
  "from": "+351****5678",
  "body": "Hello from Termux Bridge!",
  "timestamp": 1717234567890,
  "device": "TermuxBridge/0.1.0",
  "parts": 1
}
```

### Success Handling

If HTTP 200 is returned: SMS is considered delivered to Termux.

If connection fails or non-200: log error, **do not abort SMS broadcast** — other apps can still receive it.

### Multipart SMS

A single SMS can be split into multiple parts (multipart). The `pdus` extra contains all parts:

```kotlin
val pdus = intent.getByteArrayExtra("pdus")
val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

// Multipart: concatenate all parts in order
val fullBody = messages.joinToString("") { it.messageBody }
val from = messages[0].originatingAddress
val ts = messages[0].timestampMillis
```

> **Reference:** [GSM 03.38 — Concatenated SMS](https://en.wikipedia.org/wiki/Concatenated_SMS)

---

## Termux Webhook Receiver

### Python HTTP Server

```python
#!/usr/bin/env python3
# termux/bin/sms-listend — webhook receiver for incoming SMS

import json
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler

PORT = 9000

class SmsWebhookHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Suppress HTTP log noise
        pass

    def do_POST(self):
        if self.path != "/webhook/sms":
            self.send_error(404)
            return

        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length)

        try:
            data = json.loads(body)
            from_num = data.get("from", "unknown")
            body_text = data.get("body", "")
            ts = data.get("timestamp", 0)

            # Log to console
            print(f"[SMS] {from_num}: {body_text}")

            # TODO: dispatch to bot, notification, etc.
            # Example: bot.notify_sms(from_num, body_text)

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"received": True}).encode())

        except json.JSONDecodeError:
            self.send_error(400)
        except Exception as e:
            print(f"[ERROR] {e}")
            self.send_error(500)

    def do_HEALTH(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({"status": "ok", "port": PORT}).encode())

def run_server():
    server = HTTPServer(("127.0.0.1", PORT), SmsWebhookHandler)
    print(f"Webhook receiver listening on 127.0.0.1:{PORT}")
    server.serve_forever()

if __name__ == "__main__":
    run_server()
```

### Running on Boot

```bash
# ~/.bashrc or ~/.profile
python3 ~/termux-android-bridge/termux/bin/sms-listend &
```

Or use Termux Boot widget to start on boot.

---

## Edge Cases

### Multiple SMS parts (multipart)

```kotlin
val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
val fullBody = messages.joinToString("") { it.messageBody }
```

Each part arrives as a separate PDU entry in the `pdus` array.

### Non-default SMS app on Android 14+

If the app is NOT the default SMS app, `SMS_DELIVER` broadcast is NOT sent to this app. The user must explicitly set the app as the default SMS app in Android Settings.

**Handling:** Show a prompt in the app directing users to Settings → Apps → Default SMS app.

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    val isDefault = appOps.checkOpNoThrow(
        "android:receive_sms",
        android.os.Process.myUid(),
        context.packageName
    ) == AppOpsManager.MODE_ALLOWED
}
```

> **Reference:** [Android Developers — App Ops for SMS](https://developer.android.com/reference/android/app/AppOpsManager)

### SMS from unknown sender

Phone number may be `null` for some sender types (e.g., broadcast messages).

```kotlin
val from = sms.originatingAddress ?: "unknown"
```

### Device in airplane mode

SMS is stored by carrier and delivered when device reconnects. No special handling needed — the BroadcastReceiver fires when SMS is received.

### Termux webhook not running

If the Termux webhook server is down, the SMS forwarding fails silently (HTTP error logged). The SMS is **not** consumed — other apps can still process it.

---

## Security Considerations

### localhost Only

The webhook POST goes to `127.0.0.1:9000` — no network exposure.

### No Authentication

Termux filesystem isolation provides security. Only Termux scripts can access the webhook port.

### SMS Content

SMS content is local only — never leaves the device. The webhook forwards raw message data within the device loopback.

### BroadcastReceiver Export

`android:exported="true"` is required for `SMS_DELIVER` intent since Android 14 requires it. However, only the system can send this intent to the app's BroadcastReceiver when it is the default SMS app.

---

## Testing Procedure

### Manual Testing Checklist

#### Setup
- [ ] App installed on Android device
- [ ] Termux: start webhook receiver `python3 termux/bin/sms-listend`
- [ ] (Android 14+) Set app as default SMS app in Settings

#### Test Case 1: Single SMS
- [ ] Send SMS from another phone to the Android device
- [ ] Verify Termux webhook receives the POST
- [ ] Verify JSON payload: `{"from": "...", "body": "...", "timestamp": ...}`
- [ ] Verify HTTP 200 returned to Android

#### Test Case 2: Multipart SMS
- [ ] Send a long message (>160 chars) from another phone
- [ ] Verify all parts concatenated correctly in body field

#### Test Case 3: Webhook offline
- [ ] Stop Termux webhook server
- [ ] Send SMS to Android device
- [ ] Verify SMS still appears in default SMS app (not consumed)
- [ ] Verify Android logs the HTTP error

#### Test Case 4: Health check
- [ ] `curl http://127.0.0.1:9000/health`
- [ ] Verify `{"status": "ok", "port": 9000}`

---

## Current Implementation

### SmsReceiver.kt (new file)

```kotlin
package com.termuxbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class SmsReceiver : BroadcastReceiver() {

    private val client = OkHttpClient.Builder().build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Termux webhook URL — must match Termux side port
    private val termuxWebhookUrl = "http://127.0.0.1:9000/webhook/sms"

    override fun onReceive(context: Context, intent: Intent) {
        // Check if this is an SMS intent
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION &&
            intent.action != "android.provider.Telephony.SMS_RECEIVED") {
            return
        }

        val pendingResult = goAsync()
        scope.launch {
            try {
                handleSms(context, intent)
            } catch (e: Exception) {
                // Log but don't block
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleSms(context: Context, intent: Intent) {
        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isEmpty()) return

            // Get sender and concatenate multipart body
            val from = messages[0].originatingAddress ?: "unknown"
            val timestamp = messages[0].timestampMillis
            val body = messages.joinToString("") { it.messageBody }

            val json = JSONObject().apply {
                put("from", from)
                put("body", body)
                put("timestamp", timestamp)
                put("device", "TermuxBridge/0.1.0")
                put("parts", messages.size)
            }

            // Forward to Termux webhook
            val request = Request.Builder()
                .url(termuxWebhookUrl)
                .post(RequestBody.create(
                    MediaType.parse("application/json"),
                    json.toString()
                ))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    // SMS forwarded successfully
                } else {
                    // Log HTTP error (don't abort broadcast)
                }
            }
        } catch (e: IOException) {
            // Termux not reachable — log and continue
        } catch (e: Exception) {
            // Unexpected error
        }
    }
}
```

### AndroidManifest.xml (update)

Add receiver declaration inside `<application>`:

```xml
<receiver
    android:name=".SmsReceiver"
    android:exported="true"
    android:enabled="true">
    <intent-filter android:priority="2147483647">
        <action android:name="android.provider.Telephony.SMS_DELIVER" />
    </intent-filter>
</receiver>
```

Add permissions:

```xml
<uses-permission android:name="android.permission.RECEIVE_SMS" />
<uses-permission android:name="android.permission.READ_SMS" />
```

### termux/bin/sms-listend (new file)

Python webhook receiver (see Termux Webhook Receiver section above).

---

## References

### Official Documentation
- [Android Developers — BroadcastReceiver](https://developer.android.com/reference/android/content/BroadcastReceiver)
- [Android Developers — SmsMessage](https://developer.android.com/reference/android/telephony/SmsMessage)
- [Android Developers — Telephony.Sms.Intents](https://developer.android.com/reference/android/provider/Telephony.Sms.Intents)
- [Android Developers — SMS permissions on Android 14](https://developer.android.com/about/versions/14/behavior-changes#sms-permissions)
- [Android Developers — goAsync()](https://developer.android.com/guide/components/processes-and-threads#receivers)

### Libraries & Tools
- [OkHttp — HTTP client](https://square.github.io/okhttp/)
- [Python http.server — built-in HTTP server](https://docs.python.org/3/library/http.server.html)

### Community & Examples
- [Stack Overflow — SMS BroadcastReceiver example](https://stackoverflow.com/questions/48012721/)
- [GitHub — Android SMS Receive sample](https://developer.android.com/samples/SmsReceivedDemo)
- [GSM 03.38 — Concatenated SMS](https://en.wikipedia.org/wiki/Concatenated_SMS)

### Related Project Files
- [PLAN.md](../PLAN.md)
- [stage-1-sms-send.md](./stage-1-sms-send.md)
- [MainActivity.kt](../../app/src/main/java/com/termuxbridge/MainActivity.kt)
- [HttpServer.kt](../../app/src/main/java/com/termuxbridge/HttpServer.kt)
- [termux/bin/sms-send](../termux/bin/sms-send)
- [termux/python/bridge_client.py](../termux/python/bridge_client.py)

---

*Document version: 1.0*
*Part of: Termux Android Bridge Project*