# OpenMessages

A clean-room SMS app in Kotlin + Jetpack Compose, modeled on the feature set of Samsung Messages
16.2.10.47. Samsung's APK was analyzed only to inventory features, screens and Android components
(manifest, resource strings, package layout). No Samsung code was decompiled into or copied into
this project.

## Build

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and Android SDK 37. minSdk 31 (Android 12), targetSdk 36. AGP 9.3 with built-in
Kotlin 2.3, KSP 2.3.12, Room 2.8, Firebase BoM 35.

Firebase is optional. Without `app/google-services.json` the app builds normally and OTP sync shows as
unavailable. See [docs/otp-sync.md](docs/otp-sync.md) for the Firebase setup.

The app must be the **default SMS app** to read or write messages; it asks on first launch.
For emulator testing:

```sh
adb shell cmd role add-role-holder android.app.role.SMS com.hummcare.messages
adb emu sms send 5551234567 "Your code is 482913"
```

## Feature map

| Samsung Messages component | Here |
|---|---|
| `ConversationComposer` (launcher) / main list | `ui/conversations` — list, All/Unread filter, pinned section, multi-select pin/read/block/delete |
| `ComposeMessageMms`, `WithActivity` | `ui/thread` — bubbles, day headers, links, delivery status, retry, SIM switch, segment counter |
| `NewTaskComposerActivity`, contact pickers | `ui/newmessage` — recipient chips, contact search, system picker |
| `SearchActivity` | `ui/search` — contacts and message-body search with highlighting |
| `ScheduledConversationActivity`, `SendScheduledMessageReceiver` | `data/scheduled`, `ui/scheduled` — AlarmManager, survives reboot |
| `PinnedConversationActivity` | Pins stored in Room |
| Starred messages (`menu_star`) | `ui/starred` |
| `BinConversationActivity` (Trash) | `data/trash`, `ui/trash` — 30-day retention, restore |
| `BlockNumbersAndMessagesActivity`, `BlockPhrasesSettingActivity` | `ui/settings/BlockedScreen` — platform BlockedNumberContract + phrase filter |
| `QuickResponseListActivity` | `ui/settings/QuickResponsesScreen` |
| `NotificationActionService` | `notifications/` — MessagingStyle, inline reply, mark read, copy OTP code |
| `DefaultSmsAppSmsReceiver` / `DefaultSmsAppMmsReceiver` / `NoConfirmationSendService` | `sms/SmsDeliverReceiver`, `sms/MmsWapPushReceiver`, `sms/HeadlessSmsSendService` |
| `MainSettingActivity` | `ui/settings/SettingsScreen` |
| *(new)* OTP sync to desktop | `data/account`, `data/sync`, `ui/settings/OtpSyncScreen` — optional Google sign-in, end-to-end encrypted codes in Firestore |

## Architecture

- `AppContainer` — manual DI, one per process.
- `data/telephony/TelephonyRepository` — the system SMS/MMS provider, exposed as Flows driven by
  ContentObservers.
- `data/local/LocalDatabase` — Room for data the provider has no place for: pins, drafts, stars,
  scheduled messages, trash, quick responses, blocked phrases/messages.
- `sms/SmsSender` — writes to outbox, sends multipart via SmsManager; `SmsStatusReceiver` moves
  messages to sent/failed and records delivery reports.
- `data/account/AccountRepository` — optional Google sign-in (Credential Manager → Firebase Auth).
- `data/sync/OtpSyncRepository` — when OTP sync is on, `SmsDeliverReceiver` hands it each detected
  code; it's encrypted with AES-256-GCM under a PBKDF2-derived key and written to Firestore. The
  sync key stays on the device, wrapped by the Android Keystore. Format and setup: `docs/otp-sync.md`;
  security rules: `firestore.rules`. The desktop readers live in `~/Documents/OpenMessagesMac`
  (Swift) and `~/Documents/OpenMessagesWindows` (C#/WPF).
- UI is Compose + Material 3 (dynamic color), Navigation Compose, one ViewModel per complex screen.

## Not implemented

- **MMS download and sending.** Existing MMS in the provider are displayed (text + images), but
  incoming MMS only produce a "can't download" notification. This needs a WSP/MMS PDU
  encoder/decoder plus `SmsManager.downloadMultimediaMessage`/`sendMultimediaMessage`. Don't use
  this as your daily SMS app until that's added — incoming MMS will not be saved.
- Group MMS (group threads are sent as individual SMS to each recipient).
- RCS / chat features, chatbots, Galaxy Watch/PC sync, Samsung Cloud backup, bubble themes,
  carrier-specific (KOR/CHN/TMO) screens — all depend on Samsung or carrier private APIs.
- Cell broadcast / emergency alerts, SIM message management, Class 0 (flash) SMS display.
- Localization: UI strings are inline English.
