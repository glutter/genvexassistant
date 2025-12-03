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
- `db_host`: The hostname of your PostgreSQL database. If using the official HA Postgres add-on, this is usually `core-postgresql` or the IP of the container.
- `db_user`: Database username.
- `db_password`: Database password.

## Database Setup

This add-on requires a PostgreSQL database. You can install the **PostgreSQL** add-on from the official store.
Ensure you create the `genvex` database and the `humidity_readings` table. You can use the `schema.sql` file provided in the repository to create the table.

## Dashboard

Once running, you can access the dashboard by clicking "OPEN WEB UI" or navigating to `http://<HA_IP>:8081`.
