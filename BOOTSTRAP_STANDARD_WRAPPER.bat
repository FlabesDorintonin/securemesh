@echo off
rem Optional: convert the checked SecureMesh Gradle bootstrap into a standard Gradle Wrapper (including gradle-wrapper.jar).
rem Run this once on a machine with internet before opening the project in Android Studio if the IDE insists on a standard wrapper JAR.
call gradlew.bat wrapper --gradle-version 8.13 --distribution-type bin
