# Genvex Optima 270 (Model 2010) Address Map

This document lists the known Micro Nabto addresses for the Genvex Optima 270 ventilation unit, verified via reverse engineering and testing.

## Control Setpoints (Write)

| Function | Write Address | Values | Notes |
|----------|---------------|--------|-------|
| **Fan Speed** | **24** | `0` = Off<br>`1` = Speed 1 (~30%)<br>`2` = Speed 2 (~50%)<br>`3` = Speed 3<br>`4` = Speed 4 | Confirmed working. Writing to this address changes the fan speed immediately. |
| **Mode** | *Unknown* | | Likely controls Auto/Cool/Heat modes. |

## Sensor Readings (Read)

| Function | Read Address | Unit/Conversion | Notes |
|----------|--------------|-----------------|-------|
| **Fan Speed (Status)** | **7** | N/A | **Inactive/Broken**. Always reads `0` on this firmware version. Use Duty Cycle or RPM to verify state. |
| **Supply Fan Duty** | **18** | % (Raw / 100) | E.g., `3000` = 30%, `5000` = 50%. Reliable indicator of fan state. |
| **Extract Fan Duty** | **19** | % (Raw / 100) | |
| **Supply Fan RPM** | **35** | RPM | E.g., `1068` RPM. |
| **Extract Fan RPM** | **36** | RPM | |
| **Temp Supply** | **20** | °C (Raw / 10) | E.g., `442` = 44.2°C. |
| **Temp Outside** | **21** | °C (Raw / 10) | |
| **Temp Exhaust** | **22** | °C (Raw / 10) | |
| **Temp Extract** | **23** | °C (Raw / 10) | |
| **Humidity** | **26** | % | E.g., `58` = 58%. |

## Other Potential Addresses (Unverified)

Based on `optima270.py` reference:

*   **Temp Frost Protection:** 24 (Read) - *Conflict with Fan Speed Write?*
*   **Preheat PWM:** 41
*   **Reheat PWM:** 42
*   **Bypass Active:** 53
*   **Bypass Analog:** 40
*   **Alarm:** 38
*   **Rotor Speed:** 50

## Protocol Notes

*   **Read Command:** `DATAPOINT_READ` (0x2d)
*   **Write Command:** `SETPOINT_WRITELIST` (0x2b)
*   **Endianness:** Big Endian (Network Byte Order)
