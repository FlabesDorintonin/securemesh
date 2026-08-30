# Build environment result — Domain Alignment

Final sandbox probe:

Available:

- OpenJDK 21
- `kotlinc` 1.9.0 for JVM/domain compile-oriented checks
- Kotlin Coroutines JVM JAR bundled with the local Kotlin installation
- shell / Python / zip tooling

Unavailable:

- Android SDK (`ANDROID_HOME` empty)
- Android SDK (`ANDROID_SDK_ROOT` empty)
- `adb`
- `sdkmanager`
- system Gradle installation

The project contains local `gradlew` / `gradlew.bat` bootstrap scripts, but the final real attempt:

```bash
./gradlew testDebugUnitTest assembleDebug
```

failed before Gradle/Android configuration because the sandbox cannot resolve the Gradle distribution host:

```text
GRADLE_EXIT=6
curl: (6) Could not resolve host: services.gradle.org
```

APK search after the attempt returned no files.

Therefore:

- source/domain/static/compile-oriented gates were completed;
- a real Android Gradle build was **not** completed;
- a debug APK is **not** claimed.

On Mirek's Windows machine with Android SDK Platform 36 installed, run:

```bat
gradlew.bat testDebugUnitTest
gradlew.bat assembleDebug
```

Expected output:

```text
app\build\outputs\apk\debug\app-debug.apk
```

For instrumentation tests on an emulator/phone:

```bat
gradlew.bat connectedDebugAndroidTest
```
