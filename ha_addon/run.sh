#!/bin/bash

echo "Starting Genvex Monitor Add-on..."

CONFIG_PATH=/data/options.json

# Check if config exists
if [ -f "$CONFIG_PATH" ]; then
    export GENVEX_IP=$(jq --raw-output '.genvex_ip' $CONFIG_PATH)
    export GENVEX_EMAIL=$(jq --raw-output '.genvex_email' $CONFIG_PATH)
    export DB_HOST=$(jq --raw-output '.db_host' $CONFIG_PATH)
    export DB_PORT=$(jq --raw-output '.db_port' $CONFIG_PATH)
    export DB_NAME=$(jq --raw-output '.db_name' $CONFIG_PATH)
    export DB_USER=$(jq --raw-output '.db_user' $CONFIG_PATH)
    export DB_PASSWORD=$(jq --raw-output '.db_password' $CONFIG_PATH)
    export BOOST_ENABLED=$(jq --raw-output '.boost_enabled' $CONFIG_PATH)
else
    echo "Warning: $CONFIG_PATH not found. Using environment variables or defaults."
fi

echo "Configuration:"
echo "  Genvex IP: $GENVEX_IP"
echo "  DB Host: $DB_HOST"
echo "  DB User: $DB_USER"

# Start the Java application
exec java -Djava.net.preferIPv4Stack=true -jar /app/app.jar
