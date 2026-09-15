# Genvex Assistant

A Java 17/Maven service for monitoring and controlling Genvex Optima 270/2010 ventilation systems over Micro Nabto (UDP 5570).

## Dashboard

![Genvex Assistant mobile dashboard](Screenshot%202026-08-17%20at%2022.28.32.png)

## Features

- Polls humidity, temperatures, fan state, and bypass state every 30 seconds by default.
- Detects showers only when the configured rise (default 4 percentage points) occurs within 5 minutes and exceeds the rolling humidity baseline, filtering out slow weather-related drift.
- Runs the configured boost speed while the air dries toward its pre-shower absolute moisture level. Returns to normal humidity control if 30 minutes at boost speed produce no measurable drying progress, so a stale baseline cannot hold boost indefinitely.
- Guards against heat loss in cold weather: steps humidity-driven ventilation down one speed at a time and keeps each step only while the mixing ratio is measurably still falling, never below normal speed and never above 80 % humidity.
- Applies steady-humidity, night, defrost, manual, static, and evening-cooling policies.
- Paces fan writes with a humidity deadband, setpoint read-back, and exponential backoff, so a unit that rejects a setpoint settles instead of cycling between two speeds.
- Logs one fan decision per poll and records the commanded speed and supply duty alongside the measured RPM for diagnosis.
- Publishes Home Assistant sensors and provides a responsive web dashboard.

## Run Locally

Set `GENVEX_IP` and `GENVEX_EMAIL`, then run:

```sh
export GENVEX_IP=192.168.1.100
export GENVEX_EMAIL=user@example.com
./start_monitor.sh
```

The local dashboard is at `http://localhost:8081`. See [`ha_addon/README.md`](ha_addon/README.md) for Home Assistant installation and configuration. See [`ADDRESS_MAP.md`](ADDRESS_MAP.md) for verified datapoints.

Legacy `BOOST_DURATION_MINUTES` and `HUMIDITY_RECOVERY_TOLERANCE` settings remain accepted but no longer affect shower recovery.

## Disclaimer
This software is based on reverse engineering and is not affiliated with Genvex or Nabto. Use at your own risk.
