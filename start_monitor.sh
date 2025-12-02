#!/bin/bash
# Usage: ./start_monitor.sh [DB_USER] [DB_PASSWORD]

export DB_USER=${1:-postgres}
export DB_PASSWORD=${2:-password}

echo "Starting Humidity Monitor..."
echo "Connecting to DB as user: $DB_USER"

java -cp target/genvex-integration-1.0-SNAPSHOT.jar HumidityMonitor
