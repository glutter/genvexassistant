# Genvex Monitor Add-on

This add-on runs the Java-based Genvex Ventilation Monitor.

## Installation

### Option 1: Add Repository (Recommended)

1. Go to **Settings > Add-ons > Add-on Store** in Home Assistant.
2. Click the **three dots** (⋮) in the top right corner.
3. Select **Repositories**.
4. Add this URL: `https://github.com/lutherh/genvexassistant`
5. Click **Add** and then **Close**.
6. Search for "Genvex Humidity Monitor" and click **Install**.

With this method, Home Assistant will automatically notify you when updates are available!

### Option 2: Manual Installation

1. Copy the `ha_addon` folder to the `/addons/local/` directory on your Home Assistant instance.
   - You can use the Samba Share add-on or SSH to do this.
   - The path should look like `/addons/local/genvex_monitor`.
2. Go to **Settings > Add-ons > Add-on Store**.
3. Click the **three dots** in the top right corner and select **Check for updates**.
4. You should see "Genvex Humidity Monitor" under the "Local Add-ons" section.
5. Click on it and install.

## Configuration

Before starting, configure the add-on in the **Configuration** tab:

**Required Settings:**
- `genvex_ip`: The IP address of your Genvex unit (e.g., `192.168.1.100`).
- `genvex_email`: The email/password used for the Genvex connection.

**Optional Settings:**
- `poll_interval`: How often to read data in seconds (default: 30).
- `boost_enabled`: Enable/Disable automatic fan boost on humidity rise.
- `humidity_rise_threshold`: Percentage rise in humidity to trigger boost (default: 2).
- `boost_speed`: Fan speed level for boost (default: 3).
- `normal_speed`: Fan speed level for normal operation (default: 2).
- `boost_duration_minutes`: How long boost stays active (default: 15).
- `humidity_high_threshold`: Humidity level to trigger high speed (default: 60).
- `humidity_low_threshold`: Humidity level to trigger low speed (default: 30).
- `night_start`: Start time for night mode (HH:mm, default: 23:00).
- `night_end`: End time for night mode (HH:mm, default: 06:30).
- `temp_supply_offset_raw`: Calibration offset for supply temperature (raw value, default: -300).

## Database

This add-on uses an internal SQLite database to store history. No external database configuration is required.
The database is stored in `/data/genvex.db` and persists across restarts.

## Dashboard

Once running, you can access the dashboard by clicking **OPEN WEB UI** in the add-on page (via Home Assistant Ingress).

You can also access it directly at `http://<HA_IP>:8081` if you have mapped the port in the configuration.

## Adding to Home Assistant Overview

You can embed the custom dashboard directly into your Home Assistant Overview using a **Webpage Card**.

1. Go to your Dashboard and click **Edit Dashboard** (pencil icon).
2. Click **Add Card**.
3. Search for and select **Webpage**.
4. In the **URL** field, enter: `http://<YOUR_HA_IP>:8081` (e.g., `http://192.168.0.100:8081`).

## Troubleshooting

### Update not showing up?

If you have updated the files in `/addons/local/genvex_monitor` but do not see an "Update" button in Home Assistant:

1. Go to **Settings > Add-ons > Add-on Store**.
2. Click the **three dots** (top right) and select **Check for updates**.
3. If that doesn't work, try restarting Home Assistant.
4. As a last resort, uninstall the add-on and install it again (your configuration should be preserved if you don't delete the add-on data, but backing up configuration is recommended).

## Home Assistant Sensors

The add-on exports sensors to Home Assistant. You can use the standard **History Graph** card with these entities:
- `sensor.genvex_humidity`
- `sensor.genvex_temp_supply`
- `sensor.genvex_fan_rpm`
- `sensor.genvex_fan_speed`

