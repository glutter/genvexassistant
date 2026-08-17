#!/bin/bash
set -eu

mvn -q -DskipTests compile
echo "Starting Genvex Server on port 8080..."
exec java -cp target/classes GenvexServer
