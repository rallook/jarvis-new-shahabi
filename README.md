# Jarvis

Native Android voice assistant foundation (Kotlin + Jetpack Compose) focused on one reliable workflow:

> “Open WhatsApp and send ‘I will come tomorrow’ to Rahul.”

## What v0.1 does

1. Listens with Android speech recognition (swappable later)
2. Shows live transcription in a bottom Material panel
3. Understands intent via OpenAI → structured `JarvisCommand` (never executes UI itself)
4. Opens WhatsApp with a normal launch intent
5. Uses AccessibilityService to find the chat, type the message, and locate Send
6. Asks for confirmation before sending
7. Verifies the send and speaks a short TTS reply

## Architecture

```
ui/            MainScreen + Material 3 theme
voice/         SpeechRecognizerManager (+ optional mic FGS)
ai/            JarvisBrain, OpenAIClient, AiService
commands/      JarvisCommand, CommandParser, CommandExecutor
android/       AppLauncher
accessibility/ JarvisAccessibilityService, NodeFinder, WhatsAppExecutor
tts/           JarvisTTS
state/         JarvisPhase + JarvisViewModel
```

Future action types (`CLICK`, `SCROLL`, `SWIPE`, …) are reserved in `ActionType` but not implemented yet.

## Setup

### 1. Open in Android Studio

Open this folder as a Gradle project.

### 2. API key

In the app: open **Settings** (gear icon) → **OpenAI** → paste your key → **Save key**.

The key is stored in encrypted SharedPreferences on the device and is never logged.

Optional debug fallback: set `OPENAI_API_KEY` in `local.properties` (gitignored). The in-app Settings key takes priority.

- Release builds clear the embedded BuildConfig key
- Prefer a secure backend for production (`Settings → Secure backend`)


### 3. Run on a device

Emulators often lack Google speech recognition and real WhatsApp. Prefer a physical phone with WhatsApp installed.

### 4. Permissions / Accessibility

On first launch:

1. Allow **Microphone**
2. Open **Settings → Accessibility → Jarvis** and enable the service manually  
   Jarvis cannot secretly enable accessibility.

## Voice state machine

`IDLE → LISTENING → TRANSCRIBING → THINKING → EXECUTING → CONFIRMATION → SENDING → VERIFYING → COMPLETED → IDLE`

## Security notes

`OpenAIClient` is the isolation point for moving to a secure backend:

- `BuildConfig.USE_SECURE_BACKEND`
- `BuildConfig.SECURE_BACKEND_URL`

Production should never ship an OpenAI secret inside the APK.

## Known limitations (honest)

- WhatsApp UI / view IDs change between versions and locales; the executor prefers view IDs, then content descriptions / text, never raw coordinates as the primary method.
- Contact matching refuses to guess when multiple chats match.
- Universal phone control is intentionally out of scope for this version.
