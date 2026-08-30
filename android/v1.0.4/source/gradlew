#!/bin/sh
set -eu
VERSION=8.13
SHA=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/securemesh-bootstrap"
ZIP="$BASE/gradle-$VERSION-bin.zip"
DIR="$BASE/gradle-$VERSION"
BIN="$DIR/gradle-$VERSION/bin/gradle"
if [ ! -x "$BIN" ]; then
  mkdir -p "$BASE" "$DIR"
  if command -v curl >/dev/null 2>&1; then curl -fL "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$ZIP";
  elif command -v wget >/dev/null 2>&1; then wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip";
  else echo "curl or wget is required to bootstrap Gradle" >&2; exit 2; fi
  echo "$SHA  $ZIP" | sha256sum -c -
  unzip -q -o "$ZIP" -d "$DIR"
fi
exec "$BIN" "$@"
