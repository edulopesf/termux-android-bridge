# Stage 1 — Enviar SMS (SMS Send)

> **References:**
> - [Android Developers — SmsManager](https://developer.android.com/reference/android/telephony/SmsManager)
> - [Android Developers — Request runtime permissions](https://developer.android.com/training/permissions/requesting)
> - [Android Developers — Understand permissions lifecycle](https://developer.android.com/training/permissions/managing)
> - [OkHttp Documentation](https://square.github.io/okhttp/)
> - [Android Developers — BroadcastReceiver](https://developer.android.com/reference/android/content/BroadcastReceiver)
> - [Android Developers — PendingIntent](https://developer.android.com/reference/android/app/PendingIntent)

## Overview

This document covers the SMS send functionality in the Termux Android Bridge app. The app exposes an HTTP endpoint (`POST /sms/send`) that Termux scripts call to send SMS messages via Android's `SmsManager` API.

**Stack:**
- Android: Kotlin + Jetpack Compose (minSdk 31 / Android 12)
- HTTP Server: OkHttp running on `127.0.0.1:PORT` inside the app
- Termux side: Python/Bash scripts call `http://localhost:PORT/sms/send`

---

## Android Permission Flow

> **Reference:** [Android Developers — Request runtime permissions](https://developer.android.com/training/permissions/requesting) | [One-time permissions (Android 12+)](https://developer.android.com/training/permissions/upgrading-permissions#one-time)

### Required Permissions

```xml
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.SMS" />
```

### Runtime Permission Request

The app uses `ActivityCompat.requestPermissions()` to request `SEND_SMS` at runtime (line 107-111 in `MainActivity.kt`):

```kotlin
private fun requestPermission(perm: String) {
    ActivityCompat.requestPermissions(
        this,
        arrayOf(perm),
        1001  // request code
    )
}
```

### Permission Callback

`ActivityCompat.OnRequestPermissionsResultCallback` should be implemented to handle the result. The current implementation uses the default system behavior — if the user denies the permission, the app silently fails on SMS send attempts.

### Android 12+ Behavior (API 31+)

On Android 12 (API 31) and above, `SEND_SMS` is classified as a **One-Time Permission**:

- User grants permission for a single session only
- Permission resets when the app is closed or after a period of inactivity
- App must re-request each time it needs to send SMS after being re-launched

The permission state can be checked via:

```kotlin
val granted = ContextCompat.checkSelfPermission(
    context,
    Manifest.permission.SEND_SMS
) == PackageManager.PERMISSION_GRANTED
```

### Denial Handling

When permission is denied:

1. **User denies permanently**: Show rationale dialog explaining why SMS is needed
2. **User denies once**: Request again when user attempts to send SMS
3. **System denies** (device policy): Return HTTP 403 with error message

The current HTTP endpoint implementation (`HttpServer.kt`, line 149-151) catches `SecurityException` and returns:

```json
{"error": "SmsManager is null or permission denied"}
```

---

## SmsManager API

> **Reference:** [Android Developers — SmsManager](https://developer.android.com/reference/android/telephony/SmsManager) | [Android Developers — divideMessage](https://developer.android.com/reference/android/telephony/SmsManager#divideMessage(java.lang.String))

### Class Reference

```kotlin
android.telephony.SmsManager
```

Obtained via:

```kotlin
val smsManager = context.getSystemService(SmsManager::class.java)
```

On devices without telephony (tablets, Chromebooks), `SmsManager.getDefault()` may throw or return `null`.

### Method: sendTextMessage

```kotlin
fun sendTextMessage(
    destinationAddress: String!,    // Required: recipient phone number
    scAddress: String?,            // Optional: service center address (null = default)
    text: String!,                  // Required: message text
    sentIntent: PendingIntent?,     // Optional: fired when SMS is sent (success/failure)
    deliveryIntent: PendingIntent?  // Optional: fired when SMS is delivered
): Unit
```

### Parameters

| Parameter | Required | Notes |
|-----------|----------|-------|
| `destinationAddress` | Yes | Full phone number with country code (e.g., `+351912345678`) |
| `scAddress` | No | Pass `null` to use carrier default |
| `text` | Yes | Message body, max 160 chars for single-part SMS |
| `sentIntent` | No | Use for SMS_SENT broadcast tracking |
| `deliveryIntent` | No | Use for SMS_DELIVERED broadcast tracking |

### Multi-Part SMS

For messages longer than 160 chars, use `divideMessage()`:

```kotlin
val parts = smsManager.divideMessage(message)  // Returns ArrayList<String>
for (part in parts) {
    smsManager.sendTextMessage(to, null, part, sentPI, deliveryPI)
}
```

GSM encoding supports 160 chars single-part, 153 chars per part in multi-part.

### Error Cases

| Error | Cause | Handling |
|-------|-------|----------|
| `IllegalArgumentException` | Empty destination or null text | Return HTTP 400 |
| `SecurityException` | No `SEND_SMS` permission | Return HTTP 403 |
| `NullPointerException` | Device has no telephony | Return HTTP 500 |
| `IllegalStateException` | Airplane mode, no service | Return HTTP 503 |

---

## Delivery Confirmation

> **Reference:** [Android Developers — BroadcastReceiver](https://developer.android.com/reference/android/content/BroadcastReceiver) | [Android Developers — PendingIntent](https://developer.android.com/reference/android/app/PendingIntent) | [Stack Overflow — SMS sent/delivered intents](https://stackoverflow.com/questions/40493730/sms-sent-delivered-intent-always-returns-result-ok)

### BroadcastReceiver Setup

To receive SMS send/delivery status, create a `BroadcastReceiver` and register it with appropriate actions:

```kotlin
// In AndroidManifest.xml or dynamically registered
val filter = IntentFilter().apply {
    addAction("SMS_SENT_ACTION")
    addAction("SMS_DELIVERED_ACTION")
}
```

### PendingIntent Configuration

```kotlin
// SMS_SENT Intent
val sentPI = PendingIntent.getBroadcast(
    context,
    0,
    Intent("SMS_SENT_ACTION").apply {
        putExtra("message_id", messageId)
    },
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)

// SMS_DELIVERED Intent
val deliveredPI = PendingIntent.getBroadcast(
    context,
    0,
    Intent("SMS_DELIVERED_ACTION").apply {
        putExtra("message_id", messageId)
    },
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)
```

### Broadcast Result Codes

| Result Code | Meaning |
|-------------|---------|
| `Activity.RESULT_OK` | SMS sent/delivered successfully |
| `SmsManager.RESULT_ERROR_GENERIC_FAILURE` | Generic failure |
| `SmsManager.RESULT_ERROR_RADIO_OFF` | Airplane mode is on |
| `SmsManager.RESULT_ERROR_NULL_PDU` | PDU is null |
| `SmsManager.RESULT_ERROR_NO_SERVICE` | No service available |

### Current Implementation Status

The current `HttpServer.kt` implementation (lines 144-145) passes `null` for both intents:

```kotlin
smsManager.sendTextMessage(to, null, parts[0], null, null)
```

This means:
- No feedback when SMS is sent to the network
- No feedback when SMS is delivered to the recipient
- Fire-and-forget model

**Improvement needed:** Implement proper `PendingIntent` + `BroadcastReceiver` for production use.

---

## HTTP Endpoint Design

> **Reference:** [OkHttp — Making HTTP requests](https://square.github.io/okhttp/) | [RFC 7159 — JSON message format](https://tools.ietf.org/html/rfc7159)

### Endpoint: POST /sms/send

**URL:** `http://127.0.0.1:PORT/sms/send`

**Method:** `POST`

**Content-Type:** `application/json`

### Request Body

```json
{
  "to": "+351912345678",
  "message": "Hello from Termux Bridge!"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `to` | string | Yes | Recipient phone number with country code |
| `message` | string | Yes | SMS text content |

### Success Response

**HTTP Status:** `200 OK`

```json
{
  "success": true,
  "to": "+351912345678",
  "parts": 1
}
```

### Error Responses

**HTTP Status: 400** — Missing or invalid parameters

```json
{
  "error": "missing to or message"
}
```

**HTTP Status: 403** — Permission denied

```json
{
  "error": "SEND_SMS permission not granted"
}
```

**HTTP Status: 500** — Server/SDK error

```json
{
  "error": "No service available"
}
```

**HTTP Status: 503** — Service unavailable (airplane mode, etc.)

```json
{
  "error": "SMS service unavailable"
}
```

### Response Headers

```
Content-Type: application/json
Content-Length: <body_size>
Connection: close
```

---

## Edge Cases

> **Reference:** [Android Developers — SecurityException](https://developer.android.com/reference/java/lang/SecurityException) | [GSM 03.38 — SMS Character Encoding](https://en.wikipedia.org/wiki/GSM_03.38)

### Empty Phone Number

```kotlin
if (to.isEmpty()) {
    return jsonResponse(
        JSONObject().put("error", "missing to or message").toString(),
        400
    )
}
```

Returns HTTP 400 immediately without attempting to send.

### Empty Message

```kotlin
if (message.isEmpty()) {
    return jsonResponse(
        JSONObject().put("error", "missing to or message").toString(),
        400
    )
}
```

Same behavior as empty phone number.

### Message Too Long (>160 chars)

```kotlin
val parts = smsManager.divideMessage(message)
// parts.size indicates number of segments
for (part in parts) {
    smsManager.sendTextMessage(to, null, part, null, null)
}
```

`divideMessage()` automatically splits GSM 7-bit encoded messages into 153-char segments. Unicode messages split into 67-char segments.

### No SMS Permission

```kotlin
val granted = ContextCompat.checkSelfPermission(
    context,
    Manifest.permission.SEND_SMS
) == PackageManager.PERMISSION_GRANTED

if (!granted) {
    return jsonResponse(
        JSONObject().put("error", "SEND_SMS permission not granted").toString(),
        403
    )
}
```

### SmsManager Null (Non-Phone Device)

```kotlin
val smsManager = context.getSystemService(SmsManager::class.java)
if (smsManager == null) {
    return jsonResponse(
        JSONObject().put("error", "Device does not support SMS").toString(),
        500
    )
}
```

Occurs on tablets, Chromebooks, and emulators without telephony.

### Rapid-Fire Requests (Rate Limit)

**Current:** No rate limiting implemented.

**Recommended approach:**

```kotlin
class SmsRateLimiter {
    private val lastSentTime = mutableMapOf<String, Long>()
    private val minInterval = 5000L  // 5 seconds between SMS to same number

    fun canSend(to: String): Boolean {
        val last = lastSentTime[to] ?: 0L
        return System.currentTimeMillis() - last >= minInterval
    }

    fun recordSend(to: String) {
        lastSentTime[to] = System.currentTimeMillis()
    }
}
```

### Network Unavailable

If device is in airplane mode or has no cellular service:

```kotlin
val telephonyManager = context.getSystemService(TelephonyManager::class.java)
if (telephonyManager?.serviceState?.state != TelephonyManager.SERVICE_STATE_NORMAL) {
    return jsonResponse(
        JSONObject().put("error", "SMS service unavailable").toString(),
        503
    )
}
```

---

## Security Considerations

> **Reference:** [Android Developers — App security overview](https://developer.android.com/docs/security) | [Android Developers — Network security config](https://developer.android.com/docs/topics/security/security-config)

### localhost Only

The HTTP server binds to `127.0.0.1` only (line 51-52 in `HttpServer.kt`):

```kotlin
serverSocket = ServerSocket(port)
MainActivity.addLog("Server listening on 127.0.0.1:$port")
```

This means:
- **Only apps on the same device can reach the server**
- **Network scanners cannot reach it**
- **No firewall rules needed**
- **Works behind VPN without exposure**

### No Authentication

**By design**, no authentication is required because:

1. Termux has filesystem isolation from other apps (on Android 10+)
2. Only Termux scripts can access Termux's internal storage
3. Other apps cannot reach `127.0.0.1`

**Do not add authentication** — it would break the simplicity of the bridge.

### What Happens if Another App Calls localhost

In practice:
- Other apps cannot bind to `127.0.0.1:8080` because the port is already taken
- Even if they could, they couldn't read/write Termux's filesystem to get the scripts
- Android's sandboxing prevents cross-app access to Termux data

**Exception:** If a malicious app runs as the same Linux user as Termux (same UID), it *could* theoretically call the endpoint. This is extremely unlikely and would require explicit user action to install such an app.

### Data in Transit

HTTP traffic never leaves the device — it's all local socket communication via the loopback interface. No TLS needed.

### Phone Number Validation

The current implementation does not validate phone numbers before passing to `SmsManager`. Invalid numbers will produce an error from the SMSC (Short Message Service Center).

---

## Testing Procedure

### Manual Testing Checklist

#### Setup
- [ ] Install the app on Android device
- [ ] Grant `SEND_SMS` permission (tap SMS quick action button)
- [ ] Start the HTTP server (tap START in the app)
- [ ] Verify server status shows "● Running on :8080"

#### Test Case 1: Valid SMS
- [ ] From Termux: `curl -X POST http://127.0.0.1:8080/sms/send -H "Content-Type: application/json" -d '{"to": "+351912345678", "message": "Test from Termux Bridge"}'`
- [ ] Verify response: `{"success": true, "to": "+351912345678"}`
- [ ] Verify message arrives on recipient phone

#### Test Case 2: Empty Phone Number
- [ ] From Termux: `curl -X POST http://127.0.0.1:8080/sms/send -H "Content-Type: application/json" -d '{"to": "", "message": "test"}'`
- [ ] Verify response: HTTP 400 with `{"error": "missing to or message"}`

#### Test Case 3: Empty Message
- [ ] From Termux: `curl -X POST http://127.0.0.1:8080/sms/send -H "Content-Type: application/json" -d '{"to": "+351912345678", "message": ""}'`
- [ ] Verify response: HTTP 400 with `{"error": "missing to or message"}`

#### Test Case 4: Permission Denied (if previously granted and revoked)
- [ ] Revoke SMS permission in Android Settings
- [ ] Send SMS request
- [ ] Verify response: HTTP 403 with appropriate error

#### Test Case 5: Long Message (multi-part)
- [ ] Compose message over 160 characters
- [ ] Send via endpoint
- [ ] Verify all parts arrive and are complete

#### Test Case 6: Health Check
- [ ] From Termux: `curl http://127.0.0.1:8080/health`
- [ ] Verify response: `{"status": "ok", "port": 8080}`

### Expected Behavior Summary

| Scenario | HTTP Status | Response | SMS Arrives? |
|----------|-------------|----------|--------------|
| Valid SMS | 200 | `{"success": true}` | Yes |
| Empty phone | 400 | `{"error": "..."}` | No |
| Empty message | 400 | `{"error": "..."}` | No |
| No permission | 403 | `{"error": "..."}` | No |
| No service | 503 | `{"error": "..."}` | No |
| SmsManager null | 500 | `{"error": "..."}` | No |

### Termux Script Example

```bash
#!/bin/bash
# sms-send.sh — Send SMS via Termux Bridge

PORT=8080
TO="$1"
MESSAGE="$2"

if [ -z "$TO" ] || [ -z "$MESSAGE" ]; then
    echo "Usage: sms-send.sh <phone_number> <message>"
    exit 1
fi

RESPONSE=$(curl -s -X POST "http://127.0.0.1:$PORT/sms/send" \
    -H "Content-Type: application/json" \
    -d "{\"to\": \"$TO\", \"message\": \"$MESSAGE\"}")

echo "$RESPONSE"
```

### Python Client Example

```python
#!/usr/bin/env python3
# bridge_client.py — Python library for Termux Bridge

import requests
import json

class TermuxBridge:
    def __init__(self, host="127.0.0.1", port=8080):
        self.base_url = f"http://{host}:{port}"

    def sms_send(self, to: str, message: str) -> dict:
        """Send SMS message."""
        response = requests.post(
            f"{self.base_url}/sms/send",
            json={"to": to, "message": message}
        )
        data = response.json()
        if not response.ok:
            raise Exception(data.get("error", "Unknown error"))
        return data

# Usage
if __name__ == "__main__":
    bridge = TermuxBridge()
    result = bridge.sms_send("+351912345678", "Hello from Python!")
    print(result)
```

---

## Current Implementation (HttpServer.kt)

The SMS handler in `HttpServer.kt` (lines 131-153):

```kotlin
private fun handleSmsSend(body: String): String {
    return try {
        val json = JSONObject(body)
        val to = json.optString("to", "")
        val message = json.optString("message", "")

        if (to.isEmpty() || message.isEmpty()) {
            return jsonResponse(
                JSONObject().put("error", "missing to or message").toString(),
                400
            )
        }

        val smsManager = android.app.ActivityThread.currentApplication()
            .getSystemService(SmsManager::class.java)

        val parts = smsManager.divideMessage(message)
        smsManager.sendTextMessage(to, null, parts[0], null, null)

        onLog("SMS sent to $to")
        jsonResponse(JSONObject().put("success", true).put("to", to).toString())
    } catch (e: Exception) {
        onLog("SMS ERROR: ${e.message}")
        jsonResponse(JSONObject().put("error", e.message).toString(), 500)
    }
}
```

**Limitations of current implementation:**
1. Only sends first part of multi-part messages (`parts[0]`)
2. No permission check before attempting send
3. No delivery confirmation
4. No rate limiting

---

## References

### Official Documentation
- [Android Developers — SmsManager](https://developer.android.com/reference/android/telephony/SmsManager)
- [Android Developers — Request runtime permissions](https://developer.android.com/training/permissions/requesting)
- [Android Developers — One-time permissions (Android 12+)](https://developer.android.com/training/permissions/upgrading-permissions#one-time)
- [Android Developers — BroadcastReceiver](https://developer.android.com/reference/android/content/BroadcastReceiver)
- [Android Developers — PendingIntent](https://developer.android.com/reference/android/app/PendingIntent)
- [Android Developers — App security overview](https://developer.android.com/docs/security)

### Libraries & Tools
- [OkHttp Documentation](https://square.github.io/okhttp/)
- [Jetpack Compose](https://developer.android.com/compose)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)

### Community & Examples
- [Stack Overflow — SMS sent/delivered intents](https://stackoverflow.com/questions/40493730/sms-sent-delivered-intent-always-returns-result-ok)
- [Stack Overflow — Runtime permissions Android 12+](https://stackoverflow.com/questions/66797764/android-12-one-time-permission)
- [GSM 03.38 — SMS Character Encoding](https://en.wikipedia.org/wiki/GSM_03.38)
- [RFC 7159 — JSON message format](https://tools.ietf.org/html/rfc7159)

### Related Project Files
- [PLAN.md](../PLAN.md) — Project overview and stage progression
- [HttpServer.kt](../../app/src/main/java/com/termuxbridge/HttpServer.kt) — SMS handler implementation
- [MainActivity.kt](../../app/src/main/java/com/termuxbridge/MainActivity.kt) — Permission flow in UI

---

## Next Steps

After Stage 1 is tested and verified:

1. Move to **Stage 2 — Receive SMS** (`BroadcastReceiver` for incoming SMS)
2. Implement delivery confirmation (PendingIntent + BroadcastReceiver)
3. Add rate limiting
4. Create Termux scripts in `termux/bin/` directory

---

*Document version: 1.0*
*Last updated: June 2026*
*Part of: Termux Android Bridge Project*