# Project Description

nisafone is a digital assistant for Android and iOS that records conversations and transcribes them for later processing by AI assistants.

# Functionality

- records audio and transcribes voice in real time using on-device speech recognition (Sherpa-ONNX)
- speaker identification annotates who is speaking in the transcript
- user can share transcripts with other apps when recording stops or periodically during recording
- auto-start timers let recording begin on a schedule
- history screen shows past recordings with full transcripts

# Architecture

Kotlin Multiplatform (KMP) with Compose Multiplatform UI. Modules:

```
androidApp/          — Android entry point (MainActivity, NisafoneApplication, RecordingService)
composeApp/          — shared Compose UI, navigation, DI setup
core/
  audio/             — AudioRecorder interface + Android/iOS actuals
  transcription/     — TranscriptionService interface; Android uses SherpaOnnxTranscriptionService
  data/              — SQLDelight database (NisafoneDatabase), RecordingRepository
  domain/            — Recording model, RecordingUseCases
  sharing/           — ShareService interface + Android/iOS actuals
feature/
  recording/         — RecordingScreen, RecordingViewModel, foreground service management
  history/           — HistoryScreen, HistoryViewModel, RecordingDetailScreen
  settings/          — SettingsScreen, SettingsViewModel, auto-start schedule, email settings
```

DI: Koin. Database: SQLDelight. Logging: Kermit. Speech: Sherpa-ONNX (`com.bihe0832.android:lib-sherpa-onnx`).

# Version / Release

- package: `app.s4h.nisafone.android`
- current: `versionCode = 4`, `versionName = "1.1.2"` in `androidApp/build.gradle.kts`
- always bump `versionCode` and `versionName` together for every release

# Build

```bash
# debug APK
./gradlew :androidApp:assembleDebug

# release AAB (requires signing env vars)
./gradlew :androidApp:bundleRelease

# run all tests
./gradlew test
```

Release signing reads from env vars: `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.

# CI / Release Workflows

`.github/workflows/`:
- `ci.yml` — runs on push/PR to main; builds debug APK + tests (Android) and iOS simulator framework
- `android-release.yml` — manual dispatch; builds signed AAB, uploads to Play Store internal track via `r0adkll/upload-google-play@v1`; requires `changesNotSentForReview: true` until Google completes first app review
- `ios-release.yml` — manual dispatch; builds IPA, optionally uploads to App Store Connect

GitHub secrets required for Android release:
- `KEYSTORE_BASE64` — base64-encoded keystore
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` — signing credentials
- `PLAY_STORE_SERVICE_ACCOUNT_JSON` — GCP service account JSON for `play-store-publisher@nisafone.iam.gserviceaccount.com`

# Play Store

- app is on the **internal testing** track (uploaded via CI) and **open testing** track (v1.1.2 sent for review)
- GCP project: `nisafone`; Android Publisher API enabled
- service account permissions managed via Play Console → Users & Permissions (the old "Setup → API access" page no longer exists)
- if the `PLAY_STORE_SERVICE_ACCOUNT_JSON` secret goes stale, generate a new key in GCP IAM for `play-store-publisher@nisafone.iam.gserviceaccount.com` and update the secret with `gh secret set PLAY_STORE_SERVICE_ACCOUNT_JSON < key.json`
