# Genvex Assistant

A Java-based client for communicating with Genvex ventilation systems (specifically Optima 270/2010 models) via the Micro Nabto protocol (UDP 5570).

## Overview

This project implements a reverse-engineered client for the Genvex Connect system. It allows for:
- Discovery and connection to the ventilation unit.
- Reading sensor values (Datapoints).
- Handling the specific "Notification before Response" behavior of the device.

## Protocol Details

The communication uses UDP on port 5570.
- **Handshake**: `U_CONNECT` (0x83) -> `U_CONNECT` Response (contains Server ID).
- **Commands**: Wrapped in `U_CRYPT` (0x36) payloads inside `U_DATA` (0x16) packets.
- **Quirk**: The device often sends a `U_NOTIFY` (0x34) packet immediately before the actual data response. The client must consume this notification and wait for the real data.

See `notes.txt` for detailed protocol findings.

## Usage

### Prerequisites
- Java Development Kit (JDK) 8 or higher.

### Compile and Run
```bash
javac ConnectGenvex.java
java ConnectGenvex
```

## Current Status
- [x] Connection Handshake
- [x] PING Command
- [x] Reading Datapoints (e.g., Temperature Supply)
- [ ] Writing Setpoints
- [ ] Full Datapoint Mapping

## Disclaimer
This software is based on reverse engineering and is not affiliated with Genvex or Nabto. Use at your own risk.
