#!/bin/sh
set -eu
chesspilot_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -n "${JAVA_HOME:-}" ]; then
    java_bin="$JAVA_HOME/bin/java"
else
    java_bin=java
fi
exec "$java_bin" -classpath "$chesspilot_root/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain -p "$chesspilot_root" "$@"
