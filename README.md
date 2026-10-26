# LanDrop – Xender-style File & APK Sharing

**Only the sender needs the app.**  
Receivers open the IP / QR code in any browser and download files (including APKs). No app required on the receiving side.

## Features

- Share any files + **APKs / apps**
- Local HTTP server + QR code
- Optional password protection
- Beautiful dark mobile-friendly download page
- Works over Wi-Fi or Mobile Hotspot
- Share sheet support (send to LanDrop from other apps)
- Fully open source

## Build signed APK with GitHub Actions

The workflow in `.github/workflows/build-apk.yml` builds a **signed release APK** when you provide the signing secrets.

### 1. Generate a keystore (one-time, on your computer)

```bash
keytool -genkey -v -keystore upload-keystore.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload
```

Remember the **store password**, **key password** and **alias** (`upload`).

### 2. Convert keystore to base64

```bash
# Linux / macOS
base64 -w 0 upload-keystore.jks > keystore.b64

# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("upload-keystore.jks")) | Out-File keystore.b64
```

### 3. Add GitHub Secrets

Go to your repo → **Settings → Secrets and variables → Actions → New repository secret**

Add these four secrets:

| Secret name          | Value                          |
|----------------------|--------------------------------|
| `KEYSTORE_BASE64`    | content of `keystore.b64`      |
| `KEY_ALIAS`          | `upload` (or whatever you used)|
| `KEY_PASSWORD`       | your key password              |
| `STORE_PASSWORD`     | your store password            |

### 4. Trigger the build

- Push to `main`, **or**
- Go to **Actions** tab → “Build Signed APK” → **Run workflow**

When the job finishes, download the APK from the **Artifacts** section.

## Local development

```bash
flutter pub get
flutter run
```

## How receivers get the files

1. You add files / APKs and tap **Start Share**
2. The app shows IP + QR code
3. Other person opens the link in Chrome / any browser (same Wi-Fi)
4. They download — done

No internet required. No accounts. Files never leave the local network.
