# LanDrop

Xender-style Android app: share files and APKs over local Wi‑Fi.  
**Receiver only needs a browser** (open the IP/QR link).

Built with **Kotlin + XML layouts** (same style as ScreenLink).

## Features
- Pick any files
- Share installed apps as APK
- Local HTTP server + QR code
- Mobile dark UI
- Works without internet (same Wi‑Fi)

## Build (GitHub Actions)
Push to `main` or run the workflow manually.  
Download the APK from **Actions → Artifacts**.

### Secrets for signed release APK
| Secret | Value |
|--------|--------|
| `KEYSTORE_BASE64` | base64 of your `.jks` file |
| `KEY_ALIAS` | `upload` (or your alias) |
| `KEY_PASSWORD` | keystore password |
| `STORE_PASSWORD` | same password |

If secrets are missing, a **debug APK** is built (still installable).

## How to use
1. Install LanDrop on your phone
2. Add files or **Share Apps (APK)**
3. Tap **Start Sharing**
4. Other person scans the QR or opens the link in a browser on the same Wi‑Fi
5. They download — no app needed on their side
