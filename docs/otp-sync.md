# OTP sync

When OTP sync is on, the phone encrypts each verification code it receives and stores the ciphertext in Cloud Firestore. A desktop app signed in to the same Google account downloads the records and decrypts them with the same sync key. The key never leaves either device, so Firebase stores only ciphertext and cannot read the codes.

Signing in is optional. The rest of the app works without an account.

## Firebase setup

1. Create a Firebase project.
2. Add an **Android app** with package name `com.hummcare.messages`. Register the SHA-1 of every signing key you use. Debug and release builds are currently signed with the debug key, so `./gradlew :app:signingReport` lists the SHA-1 you need. Google sign-in fails with a developer error if the SHA-1 is missing.
3. Download `google-services.json` to `app/google-services.json`. The google-services plugin is applied only when this file exists. Without it the app still builds, and OTP sync shows as "not available".
4. Under **Authentication → Sign-in method**, enable **Google**.
5. Create a **Cloud Firestore** database and deploy the rules: `firebase deploy --only firestore:rules`, using the [`firestore.rules`](../firestore.rules) file in the project root.
6. Optional but recommended: add a **TTL policy** on collection group `otps`, field `expiresAt`. Firestore then deletes old codes automatically. Clients also hide expired records themselves.
7. Set up the desktop apps:
   - **Mac** ([OpenMessagesMac](https://github.com/Open-Messages/OpenMessagesMac)): add an **Apple app** with bundle ID `com.hummcare.messages.mac` and download its `GoogleService-Info.plist`. This file supplies the OAuth client the Mac app uses for sign-in.
   - **Windows** ([OpenMessagesWindows](https://github.com/Open-Messages/OpenMessagesWindows)): in Google Cloud Console for the same project, create an OAuth client of type **Desktop app**. Put its client ID and secret, together with the Web API key and project ID, in `firebase-config.json`. See that project's README.

## Firestore layout

```
users/{uid}
  otpSync: {
    v: 1,
    kdf: "PBKDF2-HMAC-SHA256",
    iterations: 600000,
    salt: <base64, 16 bytes>,
    check: { iv: <base64>, ct: <base64> },
    updatedAt: <server timestamp>
  }

users/{uid}/otps/{autoId}
  v: 1
  iv: <base64, 12 bytes>
  ct: <base64, AES-GCM ciphertext followed by the 16-byte tag>
  createdAt: <server timestamp>
  expiresAt: <timestamp, received time + 24 h>
```

`{uid}` is the Firebase Auth uid. The uid is the same on every device signed in to the same Google account.

## Cryptography (version 1)

All base64 is standard base64 with padding (RFC 4648 §4).

**Key.** `key = PBKDF2-HMAC-SHA256(password = UTF-8(passphrase), salt, iterations, dkLen = 32)`.

- `salt` and `iterations` come from `users/{uid}.otpSync`.
- The passphrase is whatever the user typed, with leading and trailing whitespace trimmed. It is case-sensitive.

**Records.** Each record uses AES-256-GCM with a 12-byte random IV and a 128-bit tag appended to the ciphertext. The AAD (additional authenticated data) is UTF-8 encoded:

| Purpose | AAD |
|---|---|
| Key check | `openmessages/check/v1/{uid}` |
| OTP record | `openmessages/otp/v1/{uid}` |

**Key check.** The `check` value decrypts to the ASCII string `openmessages-otp-sync-check`. Clients use it to confirm that a passphrase is correct before they try to decrypt any records.

**OTP plaintext.** Each OTP record decrypts to UTF-8 JSON:

```json
{ "code": "482913", "sender": "+15551234567", "body": "Your code is 482913", "receivedAt": 1760870400000 }
```

`receivedAt` is milliseconds since the Unix epoch.

## Key lifecycle

- **First device.** The user creates a key. The app generates a random key of the form `XXXX-XXXX-XXXX-XXXX-XXXX` (Crockford base32, 100 bits), or the user types their own of at least 12 characters. The phone then writes `otpSync`.
- **Another device, or a reinstall.** The user enters the existing key, and the device verifies it against `check`.
- **Changing or resetting the key.** The phone deletes every record under `otps`, then writes a new salt and a new `check`. Other devices fail the check and must ask the user for the new key.
- **Storage on Android.** The passphrase and derived key are wrapped with a non-exportable Android Keystore key and kept in `otp_sync_key.xml`. That file is excluded from backups. Signing out or switching accounts deletes it.
