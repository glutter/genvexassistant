# Genvex Optima 270 (Model 2010) Address Map

Verified Micro Nabto addresses used by this project.

## Control Setpoints (Write)

| Function | Write Address | Values | Notes |
|----------|---------------|--------|-------|
| **Fan Speed** | **24** | `0` = Off<br>`1` = Speed 1 (~30%)<br>`2` = Speed 2 (~50%)<br>`3` = Speed 3<br>`4` = Speed 4 | Confirmed working. Writing to this address changes the fan speed immediately. |

## Sensor Readings (Read)

| Function | Read Address | Unit/Conversion | Notes |
|----------|--------------|-----------------|-------|
| **Fan Speed (Status)** | **7** | N/A | **Inactive/Broken**. Always reads `0` on this firmware version. Use Duty Cycle or RPM to verify state. |
| **Supply Fan Duty** | **18** | % (Raw / 100) | E.g., `3000` = 30%, `5000` = 50%. Reliable indicator of fan state. |
| **Extract Fan Duty** | **19** | % (Raw / 100) | |
| **Supply Fan RPM** | **35** | RPM | E.g., `1068` RPM. |
| **Extract Fan RPM** | **36** | RPM | |
| **Temp Supply** | **20** | °C ((Raw + offset) / 10) | Shared default offset is `-300`. |
| **Temp Outside** | **21** | °C ((Raw + offset) / 10) | Shared default offset is `-300`. |
| **Temp Exhaust** | **22** | °C ((Raw + offset) / 10) | Shared default offset is `-300`. |
| **Temp Extract** | **23** | °C ((Raw + offset) / 10) | Shared default offset is `-300`. |
| **Humidity** | **26** | % | E.g., `58` = 58%. |
| **Bypass Active** | **53** | Binary | `0` = closed; positive values are treated as active/open. Read-only status verified on the target unit; no write address is known. |

## Protocol Notes

*   **Read Command:** `DATAPOINT_READ` (0x2d)
*   **Write Command:** `SETPOINT_WRITELIST` (0x2b)
*   **Endianness:** Big Endian (Network Byte Order)
