# Build SecureMesh APK with GitHub Actions

The repository contains `.github/workflows/android-debug-apk.yml`.

## First build

1. Create a GitHub repository.
2. Upload/push the contents of this project so `settings.gradle.kts`, `app/`, `.github/`, etc. are at the repository root.
3. Open **Actions** → **Build SecureMesh Debug APK**.
4. Click **Run workflow** → **Run workflow**.
5. Open the completed run.
6. Download the artifact named `SecureMesh-debug-apk-<run number>`.
7. Unzip the artifact. It contains `app-debug.apk`.

The workflow runs the domain-alignment gate, JVM unit tests and `assembleDebug` before uploading the APK.
