#!/bin/sh
# Self-executing SlimeToAnvil: this script is followed by the jar, which Java reads from the end of the file.
JAVA=java
[ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ] && JAVA="$JAVA_HOME/bin/java"
exec "$JAVA" -jar "$0" "$@"
