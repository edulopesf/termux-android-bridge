# Termux-Android Bridge — Plano de Desenvolvimento

## Visão Geral

App Android que funciona como ponte entre Termux e Android, usando **Termux:API** como base de comunicação. O Termux (Python) comunica com o Android via HTTP local (`localhost:PORT`) exposto pela app.

```
┌─────────────────────────────────────────────────────────┐
│  Termux (Python)                                        │
│  ┌─────────────┐    HTTP localhost    ┌──────────────┐ │
│  │ termux-api  │ ←───────────────→  │ Android App  │ │
│  │ (existing)  │                     │ (UI + custom │ │
│  └─────────────┘                     │  endpoints)  │ │
│                                      └──────────────┘ │
│                                            ↓           │
│                                      Android APIs      │
│                                   (SMS, TTS, Camera,   │
│                                    GPS, Sensors...)    │
└─────────────────────────────────────────────────────────┘
```

## Stack Tecnológica

- **App Android:** Kotlin + Jetpack Compose
- **Min SDK:** 31 (Android 12)
- **Target SDK:** 34 (Android 14)
- **Comunicação Termux→Android:** HTTP local (OkHttp server dentro da app)
- **Base Termux:** `termux-api` (SMS, clipboard, etc.) + endpoints custom na app
- **Build:** Android Studio no PC (VS Code com Android plugin)
- **CI/CD:** GitHub Actions (build APK em cada push à branch `main`)

## Funcionalidades e Ordem de Desenvolvimento

| # | Funcionalidade | API Android | Termux API | Priority | Status |
|---|---|---|---|---|---|
| 1 | Enviar SMS | `SmsManager` | `termux-sms` | 🔴 Alta | ✅ Done |
| 2 | Receber SMS | `BroadcastReceiver` | `termux-sms` | 🔴 Alta | ⏳ Pending |
| 3 | Notificações Push Nativas | `NotificationManager` | custom HTTP | 🔴 Alta | ⏳ Pending |
| 4 | Text-to-Speech (TTS) | `TextToSpeech` | custom HTTP | 🟡 Média | ⏳ Pending |
| 5 | Leitura de Clipboard | `ClipboardManager` | `termux-clipboard` | 🟢 Baixa | ⏳ Pending |
| 6 | Localização GPS | `LocationManager` | `termux-location` | 🟡 Média | ⏳ Pending |
| 7 | Câmera — Captura Foto | `CameraX` | `termux-camera` | 🟡 Média | ⏳ Pending |
| 8 | Microfone — Gravar Áudio | `MediaRecorder` | custom HTTP | 🟡 Média | ⏳ Pending |
| 9 | Reprodução de Áudio | `MediaPlayer` | custom HTTP | 🟢 Baixa | ⏳ Pending |
| 10 | Estado do Telemóvel | `BatteryManager`, `ConnectivityManager` | custom HTTP | 🟢 Baixa | ⏳ Pending |

---

## Arquitetura

### Android App

```
app/
├── src/main/
│   ├── java/com/termuxbridge/
│   │   ├── MainActivity.kt           # Entry point + UI
│   │   ├── HttpServer.kt             # Local HTTP server (OkHttp)
│   │   ├── handlers/
│   │   │   ├── SmsHandler.kt         # SMS send/receive
│   │   │   ├── NotificationHandler.kt
│   │   │   ├── TtsHandler.kt
│   │   │   ├── ClipboardHandler.kt
│   │   │   ├── LocationHandler.kt
│   │   │   ├── CameraHandler.kt
│   │   │   ├── AudioHandler.kt
│   │   │   └── PhoneStateHandler.kt
│   │   └── services/
│   │       └── SmsReceiverService.kt # Background SMS listener
│   └── res/
│       └── ...
└── build.gradle.kts
```

### Termux Side

```
termux/
├── bin/
│   ├── sms-send.sh
│   ├── sms-listen.sh
│   ├── notify.sh
│   ├── tts-speak.sh
│   ├── clipboard-get.sh
│   ├── location-get.sh
│   ├── camera-capture.sh
│   ├── audio-record.sh
│   └── audio-play.sh
├── python/
│   ├── bridge_client.py              # Python HTTP client library
│   └── examples/
│       ├── sms_example.py
│       ├── notification_example.py
│       └── tts_example.py
└── README.md
```

---

## Detalhamento por Etapa

---

## ETAPA 1 — Enviar SMS

### Objetivo
Termux (Python/Bash) envia SMS via `termux-sms -s <number> <message>` OR via HTTP POST para a app Android.

### Fluxo
```
Termux: $ termux-sms -s +351912345678 "Hello"
  → termux-api app → SmsManager.sendTextMessage()
```

### Alternativa (se termux-sms não funcionar)
```
Python: bridge_client.sms_send("+351912345678", "Hello")
  → HTTP POST http://localhost:PORT/sms/send
  → App Android → SmsManager
```

### Permissions Android
```xml
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.SMS" />
```

### Teste
1. Enviar SMS de teste para o próprio número
2. Verificar entrega

---

## ETAPA 2 — Receber SMS

### Objetivo
Quando chega SMS ao Android, o Termux recebe webhook (ou lê via polling).

### Fluxo
```
Android SMS arrives → BroadcastReceiver → HTTP POST localhost → Termux webhook handler
```

### Permissions Android
```xml
<uses-permission android:name="android.permission.RECEIVE_SMS" />
<uses-permission android:name="android.permission.READ_SMS" />
```

### Termux side
```bash
# Webhook receiver em Python
python3 -m http.server 8080 --directory /data/data/com.termux/files/home/termux-android-bridge/termux/webhooks/
```

### Teste
1. Enviar SMS do telemóvel para si mesmo
2. Verificar que o Termux recebe o conteúdo

---

## ETAPA 3 — Notificações Push Nativas

### Objetivo
Termux envia notificação push para o Android que aparece na notification bar.

### Fluxo
```
Termux: POST /notification {title: "Alert", body: "Bot is down!", priority: high}
  → App Android → NotificationManager.notify()
```

### Permissions
```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### Channels
- Canal `alerts` (high priority)
- Canal `info` (default)
- Canal `silent` (low priority)

### Teste
1. Enviar notificação do Termux
2. Ver notificação no Android

---

## ETAPA 4 — Text-to-Speech

### Objetivo
Termux envia texto → Android fala via TTS engine.

### Fluxo
```
Termux: POST /tts {text: "Hello", lang: "pt-PT", rate: 1.0}
  → App Android → TextToSpeech.speak()
```

### Permissions
```xml
<uses-permission android:name="android.permission.TTS" />
```

### Teste
1. Enviar frase em português
2. Android fala a frase

---

## ETAPA 5 — Leitura de Clipboard

### Objetivo
Termux lê/escreve clipboard do Android.

### Fluxo
```
Termux: GET /clipboard → {content: "...", timestamp: ...}
Termux: POST /clipboard {content: "..."}
```

### Permissions
```xml
<uses-permission android:name="android.permission.READ_CLIPBOARD" />
<uses-permission android:name="android.permission.WRITE_CLIPBOARD" />
```

### Termux API alternativa
`termux-clipboard-get` e `termux-clipboard-set` já existem no termux-api.
Avaliar se precisamos de duplicar ou se a app só complementa.

### Teste
1. Copiar texto no Android
2. Ler do Termux
3. Escrever do Termux
4. Verificar no Android

---

## ETAPA 6 — Localização GPS

### Objetivo
Termux obtém localização atual do Android.

### Fluxo
```
Termux: GET /location {provider: "gps"} → {lat: 39.4, lon: -8.4, accuracy: 10, timestamp: ...}
```

### Permissions
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

### Termux API alternativa
`termux-location` já existe e comunica com o termux-api app.

### Estratégia
1. Usar `termux-location` se cobrir necessidades
2. Adicionar endpoint na app para localização em background (background location updates)

### Teste
1. Obter localização atual
2. Verificar coordenadas no Termux

---

## ETAPA 7 — Câmera (Captura Foto)

### Objetivo
Termux captura foto através da câmara do Android.

### Fluxo
```
Termux: POST /camera/capture {save_path: "/sdcard/photo.jpg", quality: 85}
  → App Android → CameraX → save to file
```

### Permissions
```xml
<uses-permission android:name="android.permission.CAMERA" />
```

### Termux API alternativa
`termux-camera` já existe para captura básica.

### Estratégia
Avaliar se `termux-camera` é suficiente. A app custom dá mais controlo (flash, foco, resolução).

### Teste
1. Capturar foto
2. Transferir para Termux via `termux-pull` ou shared storage

---

## ETAPA 8 — Microfone (Gravar Áudio)

### Objetivo
Termux inicia gravação de áudio no Android.

### Fluxo
```
Termux: POST /audio/record {path: "/sdcard/rec.ogg", format: "ogg", duration_seconds: 30}
  → App Android → MediaRecorder
```

### Permissions
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

### Teste
1. Iniciar gravação de 10s
2. Reproduzir no Android para verificar

---

## ETAPA 9 — Reprodução de Áudio

### Objetivo
Termux envia ficheiro de áudio para o Android reproduzir (alertas sonoros, TTS cache, etc.).

### Fluxo
```
Termux: POST /audio/play {file_path: "/sdcard/alert.ogg", volume: 0.8}
  → App Android → MediaPlayer
```

### Permissions
```xml
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

### Teste
1. Enviar ficheiro OGG do Termux
2. Android reproduz

---

## ETAPA 10 — Estado do Telemóvel

### Objetivo
Termux obtém estado do Android (bateria, rede, chamadas).

### Fluxo
```
Termux: GET /phone_state → {battery_level: 85, charging: true, network: "wifi", signal: 4}
```

### Permissions
```xml
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

### Teste
1. Obter estado completo
2. Verificar valores corretos

---

## UI da App (Jetpack Compose)

### Ecrã Principal
- Status da conexão com Termux
- Logs em tempo real
- Botões de teste rápido para cada funcionalidade

### Ecrã de Configuração
- IP/porta do servidor HTTP
- Permissions status
- Configurações por funcionalidade

### Ecrã de Logs
- Histórico de operações
- Filtros por tipo (SMS, Notificações, TTS, etc.)

---

## Processo de Desenvolvimento

### Regra Principal
> **Cada etapa é testada e verificada antes de avançar para a próxima.**

### Ciclo por Etapa
1. Agente Research escreve documentação da etapa
2. Agente Dev escreve código Kotlin + Python
3. Teste manual no dispositivo
4. Commit da etapa ao repositório
5. Só depois avançar para próxima etapa

---

## GitHub Actions — Build Automático

```yaml
# .github/workflows/build.yml
name: Build Android APK

on:
  push:
    branches: [main, develop]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Setup Java
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Setup Android SDK
        uses: android/actions-setup@v2
      - name: Build APK
        run: ./gradlew assembleDebug
      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: apk
          path: app/build/outputs/apk/debug/*.apk
```

---

## Repositório GitHub

- **URL:** `git@github.com:edulopesf/termux-android-bridge.git`
- **Main branch:** `main`
- **Development branch:** `develop`
- **Commits por etapa:** Um commit por funcionalidade testada

---

## Notas Importantes

1. **termux-api app** precisa de estar instalada no Android para as funcionalidades base
2. A app custom adiciona os endpoints que o termux-api não cobre (TTS, audio playback, phone state, etc.)
3. Permissions são pedidas dinamicamente (não todas no install time)
4. Servidor HTTP usa `127.0.0.1` (localhost only) — segurança por design
5. Logs guardados localmente para debugging

---

## Próximo Passo

**ETAPA 1 — Enviar SMS**
- Documentação completa
- Código Kotlin
- Scripts Termux
- Teste funcional