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
- Humidity recovery requires a rise of at least `humidity_rise_threshold` percentage points (default 4) within 5 minutes and above the rolling baseline. Slow weather-related drift does not qualify; ordinary high-humidity protection still applies. Modest rises use gentle recovery: speed 2, bounded by the configured `boost_speed` and never below `normal_speed`. Full `boost_speed` requires a rise above the frozen baseline of at least 8 points or twice `humidity_rise_threshold`, whichever is greater. The configured humidity deadband applies on the way down, capped so full boost is not held below the initial rise threshold. With defaults, full boost starts at 8 points and steps down below 5; another rise must reach 8 to escalate again. Very-high-humidity protection and evening cooling can still request a higher speed.
- Recovery ends when the air is as dry as the frozen pre-rise baseline, measured on absolute moisture so cooling the house does not artificially prolong recovery. After a restart, only the relative-humidity baseline survives, so recovery finishes on that comparison.
- Active recovery also returns to normal humidity control when a full 30 minutes at the observed gentle recovery speed or higher produces less than 0.3 g/kg of drying (2 humidity percentage points if extract temperature is unavailable). This applies to restored recovery too, independently of the heat-loss guard. Continued drying keeps recovery active; a new rapid rise starts a fresh window. Manual control, monitor/static mode, defrost, airflow below gentle recovery speed, missing readings and changes between moisture/RH measurement restart observation. A restart preserves the recovery baseline but begins a fresh 30-minute observation window. High-humidity protection remains active after stalled recovery ends.
- `heat_loss_guard_enabled` keeps a humidity event from cooling the house. Below `heat_loss_indoor_temp_c` indoors and more than `heat_loss_temp_delta_c` warmer inside than out, the guard takes one speed off the humidity target, watches for `heat_loss_probe_minutes`, and keeps that step only if the mixing ratio fell by `heat_loss_progress_g_per_kg`. If it did not, one speed is given back and held until the air dries further on its own. It never goes below `normal_speed`, never steps down while humidity is at or above `humidity_very_high_threshold`, and yields to manual override, static mode and evening cooling.
- While a humidity event is actually running the guard leaves the fan alone: it does not step down while the mixing ratio is still within `heat_loss_peak_margin_g_per_kg` of its peak, for as long as that peak is less than `heat_loss_probe_minutes` old. The age limit lets steady damp air be stepped down eventually. A rise of `heat_loss_peak_margin_g_per_kg` above the level last measured releases any withheld speeds, but only up to the current humidity target: a modest event returns to gentle recovery, not full shower boost.
- `monitor_only` disables writes. Static and manual controls override automatic control.
- Evening cooling requires an open bypass and suitable temperatures; normal humidity/night control resumes when it ends.
- `humidity_hysteresis` is a deadband around every humidity threshold, so a reading that hovers on a threshold does not flip the fan target on each poll.
- Fan setpoints are paced: a raise is sent at once, a reduction waits `fan_min_command_interval_seconds`, and a setpoint the unit keeps reverting is retried every `fan_retry_interval_seconds` until `fan_retry_attempts_before_backoff`, after which the wait doubles up to `fan_max_retry_interval_seconds` and a warning is logged. Brief acceptance does not reset this backoff: the unit must hold the setpoint continuously for `fan_max_retry_interval_seconds` (default 30 minutes). A new target or an immediate manual command starts a fresh retry sequence.
- Some firmware reads address 24 as zero even while correctly running the requested speed. When zero contradicts a running fan, startup and feedback use the duty-derived speed instead; the monitor does not rewrite a speed the fan is already holding.
- `boost_duration_minutes` and `humidity_recovery_tolerance` are accepted only for upgrade compatibility.

All optional settings and defaults are shown in the Configuration tab and defined in `config.json`.

## Database

History and active shower state persist in `/data/genvex.db`.

## Dashboard

Open **OPEN WEB UI** in the add-on page, or use `http://<HA_IP>:8081` when the port is mapped. It can also be embedded in a Home Assistant Webpage card.

- Operating state shows the recorded control reason and separates observed stage, policy request, limited target and commanded stage. The observed stage is estimated from fan duty, not a firmware confirmation of the command.
- History starts with humidity and fan stage; temperature and custom views are separate. Select 3/6/12 hours, day, week or month, then narrow the visible interval with the local-time inputs or window slider. Automatic polling preserves zoom; changing the history range resets it.
- Triangle markers and the recent-event list show changes in target, command or decision reason. New reasons are recorded from version 1.80 onward; earlier readings have no recorded explanation. Week/month downsampling preserves recorded control changes.
- Moisture is shown in g/kg with a signed 30-minute difference: negative means drying. This needs 30 minutes of continuous valid observations after startup or an interruption. Relative-humidity and moisture recovery baselines are labelled separately; the moisture baseline is unavailable after a restart.
- Freshness uses the last successful device poll, with a stale threshold of at least 75 seconds or 2.5 polling intervals. A failed poll is flagged immediately; a responding web API alone does not mean the device readings are current. Unavailable history is reported independently of live data.

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
