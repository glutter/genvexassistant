# Genvex Assistant

A Java 17/Maven service for monitoring and controlling Genvex Optima 270/2010 ventilation systems over Micro Nabto (UDP 5570).

## Dashboard

![Genvex Assistant mobile dashboard](Screenshot%202026-08-17%20at%2022.28.32.png)

The dashboard shows the controller's decision alongside the observed, targeted and commanded fan stages. Humidity/fan and temperature views include 3/6/12-hour ranges, retained zoom and recorded control-event explanations. Absolute moisture and its signed 30-minute change show drying progress; device sample time distinguishes stale readings from an unreachable API. Event reasons are available only for readings recorded with version 1.80 or later.

Temporary manual boosts have a countdown and stop control that returns control to automatic evaluation, unless monitor-only mode is enabled. Click recorded events to zoom around them; history range, view and series selections survive reloads. Missing measurements appear as gaps. A separate rolling 24-hour overview reports observed time per fan stage, automatic target increases and unobserved time, independent of the graph range.

## Features

- Polls humidity, temperatures, fan state, and bypass state every 30 seconds by default.
- Starts humidity recovery when the configured rise (default 4 percentage points) occurs within 5 minutes and exceeds the rolling humidity baseline, filtering out slow weather-related drift.
- Uses speed 2 for modest rises. With defaults, an 8-point rise above the frozen baseline selects full boost; falling below 5 points returns to gentle recovery. Very high humidity still gets priority.
- Dries toward the pre-rise absolute moisture level. Returns to normal humidity control if 30 minutes at gentle recovery speed or higher produce no measurable drying progress, so a stale baseline cannot hold recovery indefinitely.
- Guards against heat loss in cold weather: steps humidity-driven ventilation down one speed at a time and keeps each step only while the mixing ratio is measurably still falling, never below normal speed and never above 80 % humidity.
- Applies steady-humidity, night, defrost, manual, static, and evening-cooling policies.
- Paces fan writes with a humidity deadband, setpoint read-back, and exponential backoff, so a unit that rejects a setpoint settles instead of cycling between two speeds.
- Logs one fan decision per poll and records the commanded speed and supply duty alongside the measured RPM for diagnosis.
- Publishes Home Assistant sensors and provides a responsive web dashboard.

## Run Locally

Set `GENVEX_IP` and `GENVEX_EMAIL`, then run:

```sh
export GENVEX_IP=192.168.1.100
export GENVEX_EMAIL=user@example.com
./start_monitor.sh
```

The local dashboard is at `http://localhost:8081`. See [`ha_addon/README.md`](ha_addon/README.md) for Home Assistant installation and configuration. See [`ADDRESS_MAP.md`](ADDRESS_MAP.md) for verified datapoints.

Legacy `BOOST_DURATION_MINUTES` and `HUMIDITY_RECOVERY_TOLERANCE` settings remain accepted but no longer affect shower recovery.

## Native hosting

The same `ha_addon/app.jar` also runs as a plain service, for example under systemd, without Home Assistant. The hosting profile is chosen by `GENVEX_HOSTING` (`addon`, `native` or `local`). If it is unset, `addon` is used when `SUPERVISOR_TOKEN` is present and `local` otherwise, so the add-on needs no new settings and behaves as before.

| Setting | `addon` | `native` | `local` |
|---|---|---|---|
| Database | `/data/genvex.db` | `GENVEX_DB_PATH`, else `$STATE_DIRECTORY/genvex.db`, else startup fails | `GENVEX_DB_PATH`, else `genvex.db` |
| `GENVEX_WEB_BIND` | `0.0.0.0` | `127.0.0.1` | `0.0.0.0` |
| `GENVEX_WEB_PORT` | `8081` | `8081` | `8081` |
| Home Assistant sensors and `sun.sun` | on | off | off |
| Stops with exit 1 on web bind or database error | no | yes | no |

The database path, bind address and port are fixed in the add-on because ingress depends on them.

- **Configuration:** every add-on option is an environment variable of the same name in upper case; see [`native/genvex.env.example`](native/genvex.env.example). Turn an existing add-on `options.json` into that format with `jq -r 'del(.cooling_target_temp) | to_entries[] | "\(.key|ascii_upcase)=\(.value)"' options.json`. Keep the file readable by root only: it contains the unit's address and account e-mail.
- **Sunset:** set `GENVEX_LATITUDE` and `GENVEX_LONGITUDE` (degrees, north and east positive). Evening cooling then starts when the sun passes -0.833 degrees, computed locally. Without them it starts at `COOLING_FALLBACK_START`. Home Assistant's `sun.sun`, when available, still takes precedence.
- **Time zone:** the JVM's default zone is used for `NIGHT_START`, `NIGHT_END` and `COOLING_FALLBACK_START`. Set `TZ`. History is stored in UTC.
- **JVM:** run with `-Djava.net.preferIPv4Stack=true`, as `run.sh` does.
- **Health:** `GET` or `HEAD /healthz` answers 200 while the web server, database and poll loop work, and 503 otherwise or during shutdown. It does not depend on the ventilation unit answering; that is reported in `device` (`ok`, `stale`, `failed`, `awaiting`). `/healthz?require=device` also returns 503 unless `device` is `ok`.
- **Shutdown:** `SIGTERM` ends the service within about 8 seconds with exit code 143. A running restart sequence is allowed to finish on speed 1; no new fan speed is written.
- **Host check:** `GENVEX_ALLOWED_HOSTS` (comma-separated, optional) rejects other `Host` headers with 421 and other `Origin` headers on `POST` with 403. Loopback names are always allowed. The API has no login, so bind it to loopback and put access control in front of it.

## Validation

Run `node --test src/test/*.test.cjs` for the dashboard state, history and zoom tests and the check that the add-on and native configuration defaults agree. Run `./publish.sh --build-only` for the Java tests and local add-on package, without staging, committing or pushing.

## Disclaimer
This software is based on reverse engineering and is not affiliated with Genvex or Nabto. Use at your own risk.
