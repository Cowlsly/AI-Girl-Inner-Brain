# Privacy Policy

**Maskan — Private AI Chat**
**Effective date:** May 25, 2026
**Last updated:** September 22, 2026

---

## Overview

Maskan is a privacy-first, open-source Android app that lets you chat with AI providers using your own API keys (BYOK — Bring Your Own Key). This privacy policy explains what data Maskan handles, how it is stored, and what leaves your device.

Maskan has no backend server, no user accounts, no analytics, no telemetry, no crash reporting, and no advertising. The full source code is publicly available under the GPL-3.0 license at [github.com/humammalhas/maskan](https://github.com/humammalhas/maskan).

---

## Data that stays on your device

All of the following data is stored locally on your device and is never transmitted to the developer or any third party:

- **Conversations and messages** — Stored in a local SQLCipher-encrypted database (AES-256-CBC). Messages, including any attached images or text files, remain on your device unless you choose to export them or send them to an AI provider.
- **Generated images** — Pictures you create with an image model are stored as AES-256-GCM encrypted files in the app's private storage, using a key held in the Android Keystore. They are never written to your photo gallery automatically and never leave the app unless you choose to save or share them. Deleting a conversation deletes its images. Because they live in app-private storage, they are removed if you uninstall Maskan — save any you want to keep.
- **API keys** — Encrypted with AES-256-GCM using the Android Keystore system and stored in Android's EncryptedSharedPreferences. Keys are never logged, transmitted to the developer, or stored in plain text.
- **Camera photos** — A photo you take from inside a chat is downscaled on your device and stored inside the message, in the same encrypted database. Maskan uses the system camera app and requests no camera permission of its own. The photo is sent only to the AI provider you send it to, and only when you send it.
- **Documents** — When you attach a PDF, Word or Excel file, its text is extracted on your device and stored in the encrypted database; the file itself is not copied. Nothing is read until you attach the file.
- **Folder instructions and memory** — The instructions and memory of a folder are stored in the encrypted database and sent to the provider as part of each request made inside that folder.
- **App settings and preferences** — Language choice, dialect, selected provider, display preferences, and folder organization are stored locally.
- **The on-device model** — If you choose the "On this phone" provider, a language-model file (Qwen2.5 1.5B Instruct, about 1.6 GB) is downloaded, only when you tap Download, from Maskan's own GitHub release at `github.com/humammalhas/maskan/releases/download/model-qwen2.5-1.5b/`, verified against a SHA-256 built into the app, and kept in the app's private storage. Chats with that provider never leave your device. Deleting the model from Settings removes the file.
- **Backup files** — Settings → Backup writes one file, wherever you choose through the system file picker, containing your chats, folders, documents, settings **and your API keys**. It is encrypted with a password you choose (PBKDF2, AES-256-GCM); Maskan cannot open it without that password and cannot recover the password. The file's header is unencrypted by design so that a restore can show you when it was made and how many chats it holds before you type anything; the header contains no message text and no key. Maskan never sends a backup file anywhere. Because the file contains your API keys, keep it as you would keep a password. Restoring a backup replaces everything on the phone with the contents of the file.

---

## Data transmitted to third-party AI providers

When you send a chat message, Maskan transmits the following to the AI provider you have configured:

- Your message text (and attached images, camera photos, or text-file contents, if applicable)
- The text of a document you attached, sent in parts, and the notes the provider wrote about the earlier parts
- The instructions and memory of the folder the chat is in, and the dialect guidance for the language you chose
- The description you write when generating an image or a video, sent to the provider that makes it; a photo you attach for editing or for animating is sent to that provider as well
- When a clip is made on a cloud provider, the app asks that provider for the job's status until the clip is ready, then downloads it; the job identifier is kept on your device only until the clip has arrived
- If you use the AI prompt helper, the description is first sent to your selected chat model at the same provider, which rewrites it before you approve it
- Recent conversation history (up to 50 messages for context)
- Your system prompt, if one is selected
- Your API key, as an authentication header

**You choose which provider to use.** Maskan supports 13 providers: On this phone (a model running on your own device, which sends nothing anywhere), DeepSeek, OpenAI, Anthropic Claude, Google Gemini, Groq, Together AI, Mistral, Venice AI, OpenRouter, Ollama, LM Studio, and custom URL endpoints. No data is sent to any cloud provider until you configure an API key and actively send a message.

**Maskan does not control how providers handle your data.** Each provider has its own privacy policy and terms of service. You are responsible for reviewing and accepting those terms when you obtain your API key. Maskan acts solely as a client — it sends your messages to the provider you selected and displays the response.

The app's network access is restricted via Android's network security configuration to only the hosts associated with providers you have enabled, plus GitHub for the optional on-device model download.

---

## Permissions

Maskan requests these Android permissions:

- **INTERNET** — Required to communicate with the AI provider APIs you configure.
- **FOREGROUND_SERVICE** and **FOREGROUND_SERVICE_DATA_SYNC** — A video clip takes minutes to make. While one is being made, the app runs a foreground service so that Android lets it keep checking on the clip with the screen off. It does nothing else and stops when the clip has arrived, failed, or been cancelled.
- **POST_NOTIFICATIONS** — Shows a quiet progress notification for a video that is being made, with a Cancel button, and an "Image ready" notice when a drawing finishes with the app off screen. Notifications are generated on your device only and contain no message content. You can decline this permission; the video is still made.

Voice input launches the Android system's speech recognizer through an intent; Maskan does not request a microphone permission and does not record, store, or transmit audio itself. Taking a photo uses the system camera app; Maskan requests no camera permission. Backup and restore go through the system file picker; Maskan requests no storage permission.

Voice narration (text-to-speech) uses whichever text-to-speech engine you have set as your device's default; Maskan sends it only the on-screen reply text to be spoken aloud, locally on your device.

---

## On-device security options

Maskan includes an optional **block screenshots** setting (off by default). When enabled, it sets the Android `FLAG_SECURE` flag on the app window, which prevents screenshots and screen recording of the app and hides it from the recent-apps preview. This is a local security control and involves no data collection.

---

## Data collection summary

| Category | Collected by Maskan | Shared with developer | Shared with third parties |
|----------|--------------------|-----------------------|--------------------------|
| Personal information | No | No | No |
| Conversations | Stored locally (encrypted) | No | Sent to your chosen AI provider when you send a message |
| Generated images | Stored locally (encrypted) | No | Never uploaded; created by the provider you chose and returned to your device |
| Camera photos and documents | Stored locally (encrypted) | No | Sent to your chosen AI provider when you send them |
| Backup files | Written where you choose, encrypted with your password | No | Never sent by Maskan; you decide where the file goes |
| API keys | Stored locally (encrypted) | No | Sent to your chosen AI provider as authentication |
| Analytics / telemetry | No | No | No |
| Crash reports | No | No | No |
| Device identifiers | No | No | No |
| Location data | No | No | No |
| Advertising data | No | No | No |

---

## Data deletion

All data is stored on your device. You can delete it at any time by:

- Deleting individual conversations within the app
- Removing API keys from the settings screen
- Deleting the on-device model from Settings
- Deleting any backup file you made, wherever you saved it
- Clearing the app's data through Android settings
- Uninstalling the app

There is no server-side data to delete because Maskan has no server.

---

## Children's privacy

Maskan is not directed at children under 13. The app does not knowingly collect personal information from children. Since Maskan stores all data locally and collects no data from users, no special data handling for children is required.

---

## Changes to this policy

If this privacy policy is updated, the changes will be posted to this page with an updated date. Since Maskan is open source, all changes are tracked in the project's version control history.

---

## Contact

If you have questions about this privacy policy, you can reach the developer at:

**Email:** h.malhas@gmail.com
**Source code:** [github.com/humammalhas/maskan](https://github.com/humammalhas/maskan)
