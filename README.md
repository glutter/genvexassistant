# Genvex Assistant

A Java-based client for communicating with Genvex ventilation systems (specifically Optima 270/2010 models) via the Micro Nabto protocol (UDP 5570).

## Overview

## Dashboard Demo

![Dashboard Demo](demo_image.png)

The history graph can independently show or hide humidity, supply, indoor/extract, outside, and exhaust temperatures, fan RPM, fan speed, and bypass open/closed state across day, week, and month ranges.

## Protocol Details

The communication uses UDP on port 5570.
- **Handshake**: `U_CONNECT` (0x83) -> `U_CONNECT` Response (contains Server ID).
- **Commands**: Wrapped in `U_CRYPT` (0x36) payloads inside `U_DATA` (0x16) packets.
- **Quirk**: The device often sends a `U_NOTIFY` (0x34) packet immediately before the actual data response. The client must consume this notification and wait for the real data.

See `notes.txt` for detailed protocol findings.

## Known Datapoints (Optima 270)

See [ADDRESS_MAP.md](ADDRESS_MAP.md) for the complete and verified list of addresses.

## Usage

### Prerequisites
- Java Development Kit (JDK) 17 or higher.

### Run the Local API Server
This project includes a local HTTP server to control the fan speed and read status.

1. Start the server:
   ```bash
   ./start_server.sh
   ```
   The server will listen on `http://localhost:8080`.

2. **Get Status** (JSON):
   ```bash
   curl http://localhost:8080/status
   ```
   Response:
   ```json
   {
     "temp_supply": 21.5,
     "temp_outside": 10.2,
     "humidity": 45,
     "fan_duty": 30,
     "fan_rpm": 1050
   }
   ```

3. **Set Fan Speed**:
   ```bash
   # Set to Speed 2 (0=Off, 1=Low, 2=Medium, 3=High, 4=Max)
   curl -X POST "http://localhost:8080/speed?level=2"
   ```

### Run the CLI Tool
To run the standalone CLI tool for testing:
```bash
javac ConnectGenvex.java
java ConnectGenvex
```

## Humidity Control & Monitoring

A dedicated background service (`HumidityMonitor`) is available to automate fan speed based on humidity levels and time of day.

### Features
1.  **Data Logging**: Polls humidity, all four air temperatures, fan RPM, and fan speed every 30 seconds and stores them in a SQLite database.
2.  **Humidity Recovery**: Compares humidity with the rolling 30-minute average. A rise of 4 percentage points starts a 15-minute boost at speed 3, followed by recovery at speed 2 until the delta falls to 3 points.
3.  **Night Mode**: Uses fan speed 1 between 22:00 and 06:30 when active cooling is not needed.
4.  **Evening Cooling**: After sunset, uses cooler outside/supply air at speed 2, including overnight, and escalates to speed 3 only if indoor temperature does not improve.
5.  **Bypass Monitoring**: Reads bypass open/closed state from datapoint 53, stores it in history, and displays it with state-specific icons. Cooling changes fan speed only; the unit's own controller operates the bypass because no verified bypass write address is available.
5.  **General Control**:
    *   **> 60% Humidity**: Speed 3 (High)
    *   **< 30% Humidity**: Speed 1 (Low)
    *   **Normal**: Speed 2

### Setup
1.  **Database**: The application uses an embedded SQLite database (`genvex.db`). No external database configuration is required.

2.  **Run Monitor**:
    ```bash
    ./start_monitor.sh
    ```

3.  **Environment Variables** (optional):
    - `GENVEX_IP`: IP address of your Genvex unit (default: 192.168.1.100)
    - `GENVEX_EMAIL`: Email/password for Genvex connection
    - `POLL_INTERVAL`: Polling interval in seconds (default: 30)
    - `MONITOR_ONLY`: Disable automatic fan writes for passive monitoring (default: false)
    - `HUMIDITY_BASELINE_MINUTES`: Rolling historical window used as the humidity baseline (default: 30)
    - `HUMIDITY_RECOVERY_TOLERANCE`: Hysteresis below the rise threshold before recovery ends (default: 1)
    - `TEMP_SUPPLY_OFFSET_RAW`: Shared raw calibration offset for all temperature sensors (legacy name, default: -300)
    - `EVENING_COOLING_ENABLED`: Enable sunset-aware cooling (default: true)
    - `COOLING_STOP_TEMP`: Indoor/extract temperature where cooling stops in °C (default: 22.0)
    - `COOLING_START_TEMP`: Indoor/extract temperature where cooling starts in °C (default: 22.5; must be at least the stop temperature)
    - `COOLING_MIN_SUPPLY_TEMP`: Supply-air comfort floor in °C (default: 15.0)
    - `COOLING_FALLBACK_START`: Start time when Home Assistant sunset state is unavailable (default: 18:00)
    - `COOLING_ESCALATION_MINUTES`: Time without 0.3°C delta improvement before speed 3 (default: 30)

## Current Status
- [x] Connection Handshake
- [x] PING Command
- [x] Reading Datapoints (e.g., Temperature Supply)
- [x] Writing Setpoints (Fan Speed Control)
- [x] Local HTTP API
- [x] Full Datapoint Mapping

## Disclaimer
This software is based on reverse engineering and is not affiliated with Genvex or Nabto. Use at your own risk.
