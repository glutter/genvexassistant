# Changelog

## 1.26

- Reverted Java runtime to Java 17 LTS for compatibility.
- Removed RPM-based control.
- Adjusted humidity control logic: High humidity (> 65%) now defaults to Normal Speed (2) instead of High Speed (3), unless humidity is very high (>= 85%).
- Updated default High Humidity Threshold to 85%.

## 1.24

- Upgraded Java runtime to Java 21 LTS.
- Added RPM-based fan control: Maintains target RPM (default 2000) when humidity exceeds threshold (default 65%).
- Added configuration options for RPM control.
