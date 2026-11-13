#!/bin/sh
# Jiva Gradle launcher. Downloads only the pinned Gradle 8.9 wrapper JAR,
# SHA-256 verifies it, then runs the distribution specified in properties.
set -eu
BASE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$BASE/gradle/wrapper/gradle-wrapper.jar"
EXPECTED="498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17"
if [ ! -f "$JAR" ]; then
  mkdir -p "$(dirname "$JAR")"
  TMP="$JAR.download.$$"
  trap 'rm -f "$TMP"' EXIT HUP INT TERM
  URL="https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar"
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --retry 3 --silent --show-error "$URL" -o "$TMP"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$URL" -O "$TMP"
  else
    echo "curl or wget is required to bootstrap the wrapper" >&2
    exit 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL=$(sha256sum "$TMP" | cut -d ' ' -f 1)
  elif command -v shasum >/dev/null 2>&1; then
    ACTUAL=$(shasum -a 256 "$TMP" | cut -d ' ' -f 1)
  else
    echo "SHA-256 verification tool missing" >&2
    exit 1
  fi
  if [ "$ACTUAL" != "$EXPECTED" ]; then
    echo "Gradle wrapper checksum mismatch: refusing untrusted download" >&2
    exit 1
  fi
  mv "$TMP" "$JAR"
  trap - EXIT HUP INT TERM
fi
if [ -n "${JAVA_HOME:-}" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA=java
fi
exec "$JAVA" -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
