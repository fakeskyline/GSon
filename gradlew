#!/usr/bin/env sh
set -eu
GRADLE_VERSION="8.13"
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/gson-bootstrap/gradle-${GRADLE_VERSION}"
GRADLE_BIN="$GRADLE_HOME_DIR/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$(dirname "$GRADLE_HOME_DIR")"
  ARCHIVE="${TMPDIR:-/tmp}/gradle-${GRADLE_VERSION}-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "$ARCHIVE"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ARCHIVE" "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  else
    echo "curl or wget is required to download Gradle ${GRADLE_VERSION}." >&2
    exit 1
  fi
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required." >&2; exit 1; }
  unzip -q -o "$ARCHIVE" -d "$(dirname "$GRADLE_HOME_DIR")"
  rm -f "$ARCHIVE"
fi
exec "$GRADLE_BIN" "$@"
