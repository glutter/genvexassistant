# Genvex Monitor Add-on

This add-on runs the Java-based Genvex Ventilation Monitor.

## Installation

### Add Repository

1. Go to **Settings > Add-ons > Add-on Store** in Home Assistant.
2. Click the **three dots** (⋮) in the top right corner.
3. Select **Repositories**.
4. Add this URL: `https://github.com/glutter/genvexassistant`
5. Click **Add** and then **Close**.
6. Search for "Genvex Humidity Monitor" and click **Install**.

### Manual Installation

1. Copy `ha_addon` to `/addons/local/genvex_monitor` using SSH or Samba.
2. Go to **Settings > Add-ons > Add-on Store**.
3. Click the **three dots** in the top right corner and select **Check for updates**.
4. You should see "Genvex Humidity Monitor" under the "Local Add-ons" section.
5. Click on it and install.

## Configuration

Before starting, configure the add-on in the **Configuration** tab:

**Required:**
- `genvex_ip`: The IP address of your Genvex unit (e.g., `192.168.1.100`).
- `genvex_email`: The email address registered with the Genvex unit.

**Behavior:**
- A humidity rise above the rolling baseline starts shower boost at `boost_speed` until the air is as dry as that frozen baseline. The comparison is made on absolute moisture, so a house that cooled during the shower still counts as recovered instead of holding boost speed in the cold. A boost that was already running when the add-on restarted is the one exception: only the relative-humidity baseline survives a restart, so that boost finishes on the older relative comparison.
- `heat_loss_guard_enabled` keeps a humidity event from cooling the house. Below `heat_loss_indoor_temp_c` indoors and more than `heat_loss_temp_delta_c` warmer inside than out, the guard takes one speed off the humidity target, watches for `heat_loss_probe_minutes`, and keeps that step only if the mixing ratio fell by `heat_loss_progress_g_per_kg`. If it did not, one speed is given back and held until the air dries further on its own. It never goes below `normal_speed`, never steps down while humidity is at or above `humidity_very_high_threshold`, and yields to manual override, static mode and evening cooling.
- While a humidity event is actually running the guard leaves the fan alone: it does not step down while the mixing ratio is still within `heat_loss_peak_margin_g_per_kg` of its peak, for as long as that peak is less than `heat_loss_probe_minutes` old. The age limit is what lets a house that is simply damp — a steady high reading with no event at all — be stepped down eventually rather than never. A rise of `heat_loss_peak_margin_g_per_kg` above the level last measured counts as a new event and hands the full speed straight back, so each of several showers in sequence gets the full boost even when a later one is smaller than the first.
- `monitor_only` disables writes. Static and manual controls override automatic control.
- Evening cooling requires an open bypass and suitable temperatures; normal humidity/night control resumes when it ends.
- `humidity_hysteresis` is a deadband around every humidity threshold, so a reading that hovers on a threshold does not flip the fan target on each poll.
- Fan setpoints are paced: a raise is sent at once, a reduction waits `fan_min_command_interval_seconds`, and a setpoint the unit keeps reverting is retried every `fan_retry_interval_seconds` until `fan_retry_attempts_before_backoff`, after which the wait doubles up to `fan_max_retry_interval_seconds` and a warning is logged. The fan then settles at whatever speed the unit insists on instead of stepping up and down.
- `boost_duration_minutes` and `humidity_recovery_tolerance` are accepted only for upgrade compatibility.

All optional settings and defaults are shown in the Configuration tab and defined in `config.json`.

## Database

History and active shower state persist in `/data/genvex.db`.

## Dashboard

Open **OPEN WEB UI** in the add-on page, or use `http://<HA_IP>:8081` when the port is mapped. It can also be embedded in a Home Assistant Webpage card.

## Home Assistant Sensors

The add-on exports:
- `sensor.genvex_humidity`
- `sensor.genvex_temp_supply`
- `sensor.genvex_temp_outside`
- `sensor.genvex_temp_exhaust`
- `sensor.genvex_temp_extract`
- `sensor.genvex_fan_rpm`
- `sensor.genvex_fan_speed`
- `sensor.genvex_bypass`
