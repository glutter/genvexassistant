# Genvex Assistant

A Java-based client for communicating with Genvex ventilation systems (specifically Optima 270/2010 models) via the Micro Nabto protocol (UDP 5570).

## Overview

## Dashboard Demo

![Dashboard Demo](demo_image.png)

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
1.  **Data Logging**: Polls humidity, temperature, and RPM every 30 seconds and stores it in a SQLite database.
2.  **Boost Mode**: Automatically detects rapid humidity rises (e.g., during a shower) and boosts fan speed to Level 3 for 15 minutes.
3.  **Night Mode**: Forces fan speed to Level 1 (Low) between 23:00 and 06:30 for quiet operation.
4.  **General Control**:
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
    - `TEMP_SUPPLY_OFFSET_RAW`: Temperature calibration offset (default: -300)

## Current Status
- [x] Connection Handshake
- [x] PING Command
- [x] Reading Datapoints (e.g., Temperature Supply)
- [x] Writing Setpoints (Fan Speed Control)
- [x] Local HTTP API
- [x] Full Datapoint Mapping

## Disclaimer
This software is based on reverse engineering and is not affiliated with Genvex or Nabto. Use at your own risk.
