# Stage Template — Termux Android Bridge

> **WARNING:** This is a template file. Do not edit directly. Copy to `docs/stage-N-<name>.md` before using.

---

## Section 1: Overview

> **References:**
> - [Android Developers — Feature name](link)
> - [Official Library/SDK Documentation](link)
> - [Community Example / Tutorial](link)

Brief description of what this stage does and how it fits into the overall system.

**Stack:**
- Android: Kotlin + Jetpack Compose (minSdk 31)
- HTTP Server: OkHttp on `127.0.0.1:PORT`
- Termux side: Python/Bash scripts

---

## Section 2: Android Permission Flow

> **Reference:** [Android Developers — Request runtime permissions](link) | [Android 12+ one-time permissions](link)

### Required Permissions

```xml
<uses-permission android:name="android.permission.XXX" />
```

### Runtime Permission Request

```kotlin
// Code example for requesting permission at runtime
ActivityCompat.requestPermissions(
    this,
    arrayOf(Manifest.permission.XXX),
    REQUEST_CODE
)
```

### Android 12+ Behavior

Describe any special behavior on Android 12+ (one-time permissions, background access, etc.)

### Denial Handling

What happens when the user denies permission. How does the app respond?

---

## Section 3: Android API Reference

> **Reference:** [Android Developers — API Class](link) | [Method documentation](link)

### Class Reference

```kotlin
android.package.ClassName
```

Obtained via: `context.getSystemService(ClassName::class.java)`

### Key Methods

#### Method: `methodName`

```kotlin
fun methodName(param: Type): ReturnType
```

| Parameter | Required | Description |
|-----------|----------|-------------|
| `param` | Yes/No | Description |

### Error Cases

| Error | Cause | Handling |
|-------|-------|----------|
| `Exception` | Cause | HTTP status |

---

## Section 4: HTTP Endpoint Design

> **Reference:** [OkHttp Documentation](link) | [REST API Design Best Practices](link)

### Endpoint: METHOD /path

**URL:** `http://127.0.0.1:PORT/path`

**Method:** `METHOD`

**Content-Type:** `application/json`

#### Request Body

```json
{
  "field": "value"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `field` | string | Yes/No | Description |

#### Success Response

**HTTP Status:** `200 OK`

```json
{
  "success": true,
  "data": "..."
}
```

#### Error Responses

**HTTP Status: 4xx** — Client error

```json
{
  "error": "description"
}
```

---

## Section 5: Edge Cases

> **Reference:** [Stack Overflow — Related issue](link) | [Android Issue Tracker](link)

### Case 1: Description

How the implementation handles this case.

```kotlin
// Code handling edge case
```

---

## Section 6: Security Considerations

> **Reference:** [Android Security Overview](link)

### localhost Only

Server binds to `127.0.0.1` only — no network exposure.

### Authentication

No authentication required (Termux filesystem isolation).

### Data in Transit

All traffic is local loopback — no TLS needed.

---

## Section 7: Testing Procedure

### Manual Testing Checklist

#### Setup
- [ ] Install app
- [ ] Grant permissions
- [ ] Start HTTP server
- [ ] Verify server running

#### Test Cases

| # | Test | Command | Expected |
|---|------|---------|----------|
| 1 | Name | `curl ...` | Response |

#### Termux Script Example

```bash
#!/bin/bash
# script name
curl -X POST "http://127.0.0.1:8080/path" \
  -H "Content-Type: application/json" \
  -d '{"field": "value"}'
```

#### Python Client Example

```python
#!/usr/bin/env python3
import requests

class TermuxBridge:
    BASE_URL = "http://127.0.0.1:8080"

    def method_name(self, field: str) -> dict:
        r = requests.post(f"{self.BASE_URL}/path", json={"field": field})
        r.raise_for_status()
        return r.json()
```

---

## Section 8: Current Implementation

Code reference to the actual implementation in `app/src/main/java/com/termuxbridge/`.

### Limitations

1. Known limitation
2. Another limitation

---

## References

### Official Documentation
- [Android Developers — API](link)
- [Android Developers — Permissions](link)

### Libraries & Tools
- [Library Documentation](link)

### Community & Examples
- [Stack Overflow — Topic](link)
- [Tutorial / Guide](link)

### Related Project Files
- [PLAN.md](../PLAN.md)
- [MainActivity.kt](../../app/src/main/java/com/termuxbridge/MainActivity.kt)
- [HttpServer.kt](../../app/src/main/java/com/termuxbridge/HttpServer.kt)

---

*Document version: 1.0*
*Template version: 1.0*
*Part of: Termux Android Bridge Project*