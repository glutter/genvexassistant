#!/bin/bash
set -eu

echo "Building Genvex Humidity Monitor..."
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/runtime-classpath.txt

echo "Starting Humidity Monitor..."
RUNTIME_CLASSPATH=$(<target/runtime-classpath.txt)
exec java -cp "target/classes:${RUNTIME_CLASSPATH}" HumidityMonitor
