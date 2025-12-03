# Genvex Monitor Add-on

This add-on runs the Java-based Genvex Ventilation Monitor.

## Installation

1. Copy the `ha_addon` folder to the `/addons/local/` directory on your Home Assistant instance.
   - You can use the Samba Share add-on or SSH to do this.
   - The path should look like `/addons/local/genvex_monitor`.
2. Go to **Settings > Add-ons > Add-on Store**.
3. Click the **three dots** in the top right corner and select **Check for updates**.
4. You should see "Genvex Humidity Monitor" under the "Local Add-ons" section.
5. Click on it and install.

## Configuration

Before starting, configure the add-on in the **Configuration** tab:

- `genvex_ip`: The IP address of your Genvex unit.
- `genvex_email`: The email used for the Genvex connection (password).
- `boost_enabled`: Enable/Disable automatic fan boost on humidity rise.

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
5. Set the **Aspect Ratio** to something like `75%` or `100%` to fit the graph.
6. Click **Save**.

Alternatively, since version 1.3+, the add-on exports sensors to Home Assistant. You can use the standard **History Graph** card with these entities:
- `sensor.genvex_humidity`
- `sensor.genvex_temp_supply`
- `sensor.genvex_fan_rpm`

