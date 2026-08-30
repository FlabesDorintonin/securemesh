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

## CI repair notes

- AndroidX Lifecycle is pinned to `2.10.0` for the current AGP 8.13.2 / compileSdk 36 matrix.
- The first real Kotlin compile exposed a LazyList DSL issue in `MessagesScreen.kt`; this revision removes nested `item {}` calls from `let`/`forEach` scopes and applies the same hardening to Field Test, Search and More.
- Keep using GitHub Actions as the authoritative Android compile gate. If a later run fails, inspect the first Kotlin/Gradle error rather than the final `exit code 1` summary.
