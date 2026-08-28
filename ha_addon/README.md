# Genvex Monitor Add-on

This add-on runs the Java-based Genvex Ventilation Monitor.

## Installation

### Add Repository

1. Go to **Settings > Add-ons > Add-on Store** in Home Assistant.
2. Click the **three dots** (⋮) in the top right corner.
3. Select **Repositories**.
4. Add this URL: `https://github.com/lutherh/genvexassistant`
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
- A humidity rise above the rolling baseline starts shower boost at `boost_speed` until humidity returns to that frozen baseline.
- `monitor_only` disables writes. Static and manual controls override automatic control.
- Evening cooling requires an open bypass and suitable temperatures; normal humidity/night control resumes when it ends.
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
