# Changelog

## 1.40

- **Fixed Fan Speed Display bug**: Derives fan speed dynamically from actual EC-motor duty cycle (register 18) and RPM. This fixes the `-1` Fan Speed display bug during startup or when running in passive monitoring mode.
- **Unified UX & System Control**: Replaced conflicting checkboxes with a single mutually exclusive "System Control Mode" dropdown (Auto Control, Static Fan Speed, Passive Monitor).
- **Interactive Alerts & Banners**: Added live dashboard notification banners to visually highlight when a rapid humidity shower boost or a manual ventilation override is actively running, showing time remaining.

## 1.39

- The Redundancy: The identical logic for evaluating if a given time falls in the "night" interval was copied inline in two different places in HumidityMonitor.java (updateFanSpeed and checkBoostLogic).

- The Bug: The inline check (now.isAfter(NIGHT_START) || now.isBefore(NIGHT_END)) has a classic bug: if the start time is configured to be before the end time on the same day (e.g. 01:00 to 04:00), then every hour after 01:00 (such as 12:00 noon or 18:00 evening) triggers as "night".

- The Fix: Refactored that logic into a single robust helper method isNight(LocalTime time) that handles both same-day (non-crossing) and multi-day (midnight-crossing) intervals correctly under all custom Home Assistant configurations:

## 1.38

- **Historical Data View**: Added daily, weekly, and monthly chart views with smart downsampling.
- **Control Mode Toggle**: Added a runtime switch to toggle between "Active Control" and "Monitor Only" modes.
- **UI UX Improvements**: Added quick-action buttons for 30m, 1h, and 2h manual boost.
- **Backend Optimization**: Refactored API handlers for better JSON parsing and reduced duplication.

## 1.37


- Added **Static RPM Mode**: Manual override to set a fixed fan speed that bypasses humidity-based control.
- Extended data retention period from 14 days to **1 month**.
- New `/api/fan/static` endpoint for enabling/disabling static mode via API.
- Dashboard updated with Static RPM Mode controls.

## 1.32

- Fixed RPM chart scaling (set max to 4000).
- Updated demo image.

## 1.31

- Fixed chart resizing issue (endless expansion).
- Improved chart container stability.

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
