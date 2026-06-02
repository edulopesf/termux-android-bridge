# Termux Android Bridge

Ponte entre Termux e Android — acesso a APIs nativas (SMS, TTS, Câmera, GPS, etc.) via HTTP local.

## Stack

- **App Android:** Kotlin + Jetpack Compose (minSdk 31 / Android 12)
- **Base:** Termux:API como cliente no Termux
- **Comunicação:** HTTP localhost (OkHttp server na app Android)
- **Build:** Android Studio / VS Code + Android plugin

## Funcionalidades

| # | Funcionalidade | Status |
|---|---|---|
| 1 | Enviar SMS | 🟢 Done |
| 2 | Receber SMS | 🟢 Done |
| 3 | Notificações Push | 🟡 Pending |
| 4 | Text-to-Speech (TTS) | 🟡 Pending |
| 5 | Clipboard | 🟢 Pending |
| 6 | GPS / Localização | 🟡 Pending |
| 7 | Câmera (captura foto) | 🟡 Pending |
| 8 | Microfone (gravar áudio) | 🟡 Pending |
| 9 | Reprodução de Áudio | 🟢 Pending |
| 10 | Estado do Telemóvel | 🟢 Pending |

## Desenvolvimento

Consulte [PLAN.md](PLAN.md) para o plano detalhado de desenvolvimento.

### Regra
Cada funcionalidade é testada e verificada antes de avançar para a próxima.

## Repositório

```
git@github.com:edulopesf/termux-android-bridge.git
```