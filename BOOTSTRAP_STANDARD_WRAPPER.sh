#!/bin/sh
set -eu
./gradlew wrapper --gradle-version 8.13 --distribution-type bin
