# ClipSync Android

ClipSync is an experimental, open-source clipboard synchronization tool for personal Android devices. It encrypts clipboard payloads locally before relaying and storing them through a Supabase project that you control.

### Bidirectional Multi-Device Synchronization
ClipSync supports seamless cross-platform clipboard sharing. Any number of devices sharing the same Supabase configuration and custom channel secret password will synchronize automatically in real-time. This includes:
- **Android to Android**
- **Android to macOS** (via the [macOS version of ClipSync](https://github.com/nishantattrey07/ClipSync))
- **macOS to Android**

ClipSync is accountless and optimized for one person or a small trusted group using a dedicated Supabase project. It is not a hostile multi-tenant service: anyone who obtains the project's URL and publishable key can enumerate encrypted rows and metadata. Use a generated channel secret, keep project credentials private, and review the security boundaries below before deployment.

---

## Features

- **Personal Self-Hosting**: Connect a dedicated Supabase project without maintaining user accounts or an SMTP server.
- **Manual & Auto Sync Modes**: Choose to either automatically upload clipboard captures or strictly manual sync by clicking primary action commands.
- **End-to-End Encrypted**: Uses Argon2id (via native JNI binding) for key derivation and AES-256-GCM (with hardware-backed AndroidKeyStore envelope keys) for local database and cloud payload encryption.
- **Accountless Channels**: Devices using the same secret password derive the same channel identifier and encryption keys.
- **Strict Data State Management**: Built-in state machine for robust failure retries (`local`, `syncing`, `synced`, `failed`).
- **Memory Efficient**: Limits local history to 200 items, generating fast local thumbnails for smooth scrolling and caching decrypted assets safely.
- **Safe Deletion**: Deleting an item securely halts any active network tasks before removing data from your phone.
- **Smart Catch-up**: Reconnect after being offline to fetch items you missed while disconnected.

---

## How Security Works

ClipSync encrypts clipboard payloads locally, but Supabase still mediates communication and observes metadata. It is not peer-to-peer.

1. **Key Derivation:** Your secret password is fed into `Argon2id` (a memory-hard hashing function) to derive a strong Master Key.
2. **Channel Generation:** The Master Key is used via HKDF-SHA256 to generate a deterministic 64-character `channel_id`. Devices with the same password generate the exact same channel ID.
3. **Payload Encryption:** The actual clipboard contents (text or image data) are encrypted locally using AES-256-GCM before ever leaving your device.
4. **Data Transmission:** Ciphertext is sent to Supabase together with channel/device identifiers, timestamps, item kind, MIME type, sizes, and Storage paths.
5. **Decryption:** Other devices in the channel receive the encrypted blob and decrypt it locally using their locally-derived AES key.

### Security Boundary

- Clipboard text and image payloads are encrypted before upload to Supabase.
- Local clipboard history contains plaintext or image data in the Android app's Room database. Under Android 10+, this storage is private to the application sandbox. Protect the phone account and device accordingly.
- The Supabase URL and publishable key are client configuration, not authorization secrets. If they leak, an attacker can collect ciphertext/metadata and upload objects allowed by the current policies.
- Current database read policies intentionally permit clients holding the project URL and publishable key to enumerate encrypted rows and visible metadata. End-to-end encryption, a strong channel secret, and a dedicated personal project are the confidentiality boundary; this design is not suitable for hostile multi-tenant deployment.
- Storage access is bucket-wide for the `clipboard-images` bucket. Removing an item locally does not guarantee deletion of its encrypted cloud object. Empty the bucket through Supabase when cloud deletion is required.
- Every device sharing a channel secret can decrypt that channel's history. There is currently no member revocation or per-user authorization.

---

## Getting Started

### Prerequisites
- Android 10+ (API level 29+)
- JDK 17 or 21
- [Android Studio (Ladybug or newer)](https://developer.android.com/studio)
- A [Supabase](https://supabase.com) Project

### 1. Database Setup (Supabase)
Run the canonical SQL script [database_setup.sql](database_setup.sql) in your Supabase SQL Editor. This sets up the necessary tables, Row-Level Security (RLS) policies, security definer RPC functions, and storage bucket configuration.

### 2. Build & Run
1. Clone this repository and open the project in Android Studio.
2. Let Gradle sync and download dependencies.
3. Build and Run the app on a physical device or emulator.
4. Tap the Settings icon in the top right corner.
5. Enter your Supabase URL, publishable anonymous key, and a custom channel secret password.
6. Hit **Connect** and start copying!

---

## Tech Stack
- **UI:** Jetpack Compose & Material 3
- **Database / Local Cache:** Room DB
- **Key-Value Store:** AndroidX Proto DataStore
- **Backend / Realtime Sync:** [Supabase](https://supabase.com) & PostgreSQL (using WebSockets)
- **Cryptography:** AES-256-GCM, HKDF-SHA256, and native Argon2id bindings

## Project Policies
- [MIT License](LICENSE)
