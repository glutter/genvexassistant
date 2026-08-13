# Changelog

## 1.47

- Fixed dashboard fan controls after the per-poll connection change by giving commands fresh serialized sessions, returning validation/device errors, and recording control state only after acknowledgement.
- Hardened UDP response handling against delayed or foreign packets by validating endpoint, session IDs, sequence, packet lengths, command envelope, and checksum; failed handshakes now close their sockets.
- Fixed ignored `normal_speed` and `humidity_low_threshold` settings, kept fan speed monotonic as humidity rises, aligned standalone defaults with add-on defaults, and corrected shorter boost durations being extended to ten minutes.
- Reduced control latency by releasing the Genvex lock before database and Home Assistant I/O, cleared expired manual override state, and enforced mutually exclusive monitor/static modes.
- Added regression coverage for packet correlation, humidity thresholds, and boost duration boundaries.

## 1.46

- Fixed recurring datapoint timeouts by establishing a fresh Genvex UDP session for every polling cycle instead of reusing a session after the unit's idle timeout.
- Abort the current poll after the first datapoint exhausts its retries, avoiding repeated timeouts across all remaining datapoints and recovering on the next scheduled poll.

## 1.45

- Added toggleable history series for supply, indoor/extract, outside, and exhaust temperatures, humidity, fan RPM, and fan speed.
- Added backward-compatible SQLite migration and persistence for the additional temperature and fan-speed history values; older rows remain valid and render as gaps where data was not recorded.
- Updated the dashboard screenshot and responsive chart controls, including a dedicated fan-speed axis and reduced mobile time-label density.

## 1.44

- Redesigned the dashboard around a compact live-climate overview, side-by-side control panels, and a clearly separated history view.
- Added responsive mobile layouts, consistent controls and icons, live connection/update status, clearer active-mode banners, and reduced chart label density.
- Moved system restart into collapsed maintenance controls and fixed duplicate version metadata in the add-on configuration.

## 1.43

- Fixed outside, exhaust, and extract/indoor temperatures displaying 30°C too high by applying the configured raw calibration offset consistently to all temperature registers.
- Added regression coverage for the reported readings: 18.4°C supply, 24.0°C indoor/extract, 17.2°C outside, and 19.7°C exhaust.
- Applied the same shared calibration to the standalone status API and clarified the legacy `temp_supply_offset_raw` option semantics.

## 1.42

- Added configurable indoor cooling triggers: `cooling_start_temp` starts evening cooling and `cooling_stop_temp` stops it, allowing users to tune comfort and noise independently.
- Preserved existing behavior with defaults of 22.5°C to start and 22.0°C to stop, including automatic migration from the previous `cooling_target_temp` setting.
- Invalid ranges fail closed and log a clear error instead of running cooling without hysteresis.

## 1.41

- Added sunset-aware evening cooling using supply, outside, and extract/indoor temperatures. Cooling runs until quiet night mode, starts at speed 2, latches at speed 3 after insufficient progress, and stops with temperature deadbands as indoor/outdoor conditions converge.
- Added outside, exhaust, and extract/indoor temperatures to the live dashboard and Home Assistant sensors.
- Fixed rapid-rise humidity boost detection, which existed but was never called from the polling loop, and made `boost_enabled` effective without overriding steady high-humidity control.
- Added a startup `monitor_only` add-on option, defaulting to automatic control so configured cooling and humidity rules remain active after restart. Runtime dashboard mode changes last until the add-on restarts.
- Upgrading to 1.41 enables automatic control by default; set `monitor_only` to true before starting the add-on for passive telemetry only.
- Added Home Assistant HTTP timeouts so an unavailable Core API cannot block the monitoring loop indefinitely.

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
