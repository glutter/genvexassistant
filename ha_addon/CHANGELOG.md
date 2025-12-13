# Changelog

## 1.30

- UI Overhaul: Dark Mode and Mobile Friendly design.
- Updated Chart.js configuration for better visibility in dark mode.

## 1.29

- Fixed SLF4J warning by adding slf4j-simple logging implementation.

## 1.28

- Version bump to fix update triggers in Home Assistant.
- Documentation updates.

## 1.26

- Reverted Java runtime to Java 17 LTS for compatibility.
- Removed RPM-based control.
- Adjusted humidity control logic:
  - **Very High Humidity** (default 80%) -> Speed 3 (Boost)
  - **High Humidity** (default 65%) -> Speed 2 (Normal)
  - Below High Humidity -> Speed 1 (Low)
- Added `humidity_very_high_threshold` configuration option.

## 1.24

- Upgraded Java runtime to Java 21 LTS.
- Added RPM-based fan control: Maintains target RPM (default 2000) when humidity exceeds threshold (default 65%).
- Added configuration options for RPM control.
