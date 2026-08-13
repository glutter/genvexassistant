#!/bin/bash

echo "Starting Genvex Monitor Add-on..."

CONFIG_PATH=/data/options.json

# Check if config exists
if [ -f "$CONFIG_PATH" ]; then
    export GENVEX_IP=$(jq --raw-output '.genvex_ip' $CONFIG_PATH)
    export GENVEX_EMAIL=$(jq --raw-output '.genvex_email' $CONFIG_PATH)
    export POLL_INTERVAL=$(jq --raw-output '.poll_interval' $CONFIG_PATH)
    export MONITOR_ONLY=$(jq --raw-output 'if has("monitor_only") then .monitor_only else false end' $CONFIG_PATH)
    export BOOST_ENABLED=$(jq --raw-output '.boost_enabled' $CONFIG_PATH)
    export HUMIDITY_RISE_THRESHOLD=$(jq --raw-output '.humidity_rise_threshold' $CONFIG_PATH)
    export BOOST_SPEED=$(jq --raw-output '.boost_speed' $CONFIG_PATH)
    export NORMAL_SPEED=$(jq --raw-output '.normal_speed' $CONFIG_PATH)
    export BOOST_DURATION_MINUTES=$(jq --raw-output '.boost_duration_minutes' $CONFIG_PATH)
    export HUMIDITY_VERY_HIGH_THRESHOLD=$(jq --raw-output '.humidity_very_high_threshold' $CONFIG_PATH)
    export HUMIDITY_HIGH_THRESHOLD=$(jq --raw-output '.humidity_high_threshold' $CONFIG_PATH)
    export HUMIDITY_LOW_THRESHOLD=$(jq --raw-output '.humidity_low_threshold' $CONFIG_PATH)
    export NIGHT_START=$(jq --raw-output '.night_start' $CONFIG_PATH)
    export NIGHT_END=$(jq --raw-output '.night_end' $CONFIG_PATH)
    export TEMP_SUPPLY_OFFSET_RAW=$(jq --raw-output '.temp_supply_offset_raw // -300' $CONFIG_PATH)
    export EVENING_COOLING_ENABLED=$(jq --raw-output 'if has("evening_cooling_enabled") then .evening_cooling_enabled else true end' $CONFIG_PATH)
    export COOLING_STOP_TEMP=$(jq --raw-output '.cooling_stop_temp // .cooling_target_temp // 22.0' $CONFIG_PATH)
    export COOLING_START_TEMP=$(jq --raw-output '.cooling_start_temp // ((.cooling_target_temp // 22.0) + 0.5)' $CONFIG_PATH)
    export COOLING_MIN_SUPPLY_TEMP=$(jq --raw-output '.cooling_min_supply_temp // 15.0' $CONFIG_PATH)
    export COOLING_FALLBACK_START=$(jq --raw-output '.cooling_fallback_start // "18:00"' $CONFIG_PATH)
    export COOLING_ESCALATION_MINUTES=$(jq --raw-output '.cooling_escalation_minutes // 30' $CONFIG_PATH)
else
    echo "Warning: $CONFIG_PATH not found. Using environment variables or defaults."
fi

echo "Configuration:"
echo "  Genvex IP: $GENVEX_IP"
echo "  Poll Interval: $POLL_INTERVAL s"
echo "  Monitor Only: $MONITOR_ONLY"
echo "  Boost Enabled: $BOOST_ENABLED"
echo "  Temperature Sensor Offset Raw: $TEMP_SUPPLY_OFFSET_RAW"
echo "  Evening Cooling: $EVENING_COOLING_ENABLED (start $COOLING_START_TEMP C, stop $COOLING_STOP_TEMP C, supply floor $COOLING_MIN_SUPPLY_TEMP C)"

# Start the Java application
exec java -Djava.net.preferIPv4Stack=true -jar /app/app.jar
