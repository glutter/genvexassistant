#!/bin/bash
set -eu

echo "Building Genvex Humidity Monitor..."
mvn -q package

echo "Starting Humidity Monitor..."
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
exec java -jar "target/genvex-integration-${VERSION}.jar"
