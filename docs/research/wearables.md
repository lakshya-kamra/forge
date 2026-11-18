# Wearables on Android: integration paths (researched 2026-11-17)

Full data with sources is in `wearables.json`. This page gives the short version.

## Bottom line for Forge

1. **Health Connect is the main path.** One `connect-client` integration covers WHOOP, Amazfit, Fitbit/Pixel Watch, Samsung, Garmin, Oura, Polar, COROS, Withings and Xiaomi.
2. **Use cloud APIs only where they add metrics that Health Connect lacks**, and only where one person can use them without approval: WHOOP, Oura, Polar, Withings, Garmin (new Personal API) and Google Health API.
3. **Apple: there is no live path.** Apple Watch pairs only with iPhone, and HealthKit has no cloud API. The only option is importing `export.zip` from the Health app.
4. **Huawei** does not write to Health Connect. The only option is the third-party Health Sync app.

## What each brand writes to Health Connect

| Brand | HR | RHR | HRV | Sleep stages | Exercise | Calories | VO2max | Weight/BF | SpO2 | Skin temp | Resp rate | Personal API |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Google Health (Fitbit/Pixel) | Y | Y | Y | Y | Y | total | Y | Y/Y | read-only | Y | Y | Google Health API (test-user OAuth) |
| Oura | Y | ? | Y | Y | Y | Y | - | Y/- | - | - | - | Yes, OAuth, 10 users |
| Amazfit/Zepp | Y | Y | - | Y | Y | Y | Y | Y | Y | - | Y | No |
| WHOOP | exercise only | Y* | - | Y | Y | Y | - | - | Y | - | Y* | Yes, OAuth, Sandbox 10 |
| Samsung Health | Y | - | - | Y | Y | Y | Y | Y/Y | Y | - | - | On-device SDK, dev mode |
| Garmin | Y | Y | - | Y | Y | Y | - | Y/Y | - | - | - | Personal API key (Oct 2026) |
| Polar | workouts | Y | - | Y | Y | Y | Y | Y | Y | - | - | AccessLink, any Flow user |
| Xiaomi Mi Fitness | exercise | - | - | Y | Y | Y | - | Y | - | - | - | No |
| Withings | ? | ? | ? | ? | ? | ? | ? | likely | ? | ? | ? | Yes, OAuth, no contract |
| COROS | ? | ? | ? | ? | ? | ? | ? | ? | ? | ? | ? | Partner only |
| Huawei | none | | | | | | | | | | | AppGallery-only |
| Apple | none | | | | | | | | | | | none |

*WHOOP resting HR and respiratory rate need the Peak tier (according to WHOOP's article).
"?" means no official list was found; check the device's Health Connect > App permissions screen.

Most lists cross-check against Google's own page of what Google Health receives from each partner via Health Connect: https://support.google.com/googlehealth/answer/14236613

## WHOOP API (the main one worth building)

- Authorize: `https://api.prod.whoop.com/oauth/oauth2/auth`. Token: `https://api.prod.whoop.com/oauth/oauth2/token`. Base URL: `https://api.prod.whoop.com/developer`.
- Scopes: `read:recovery read:cycles read:workout read:sleep read:profile read:body_measurement offline`. The `offline` scope is required to get a refresh token.
- `state` must be 8 characters. Redirect URIs must be registered in the dashboard, and custom schemes are allowed.
- Each refresh rotates both tokens. Only one concurrent refresh succeeds, so serialize refreshes.
- Endpoints:
  - `/v2/recovery`, `/v2/cycle`, `/v2/cycle/{id}/recovery`
  - `/v2/activity/sleep`, `/v2/activity/workout`
  - `/v2/user/measurement/body`, `/v2/user/profile/basic`
- Collection paging: `limit` (max 25) and `nextToken`.
- Limits: 100 requests/min and 10,000/day. The Sandbox tier allows 10 members with no approval.
- `hrv_rmssd_milli`, `recovery_score`, `skin_temp_celsius` and `strain` are only available here, not in Health Connect.
- The token exchange needs the client secret. Do it on the PC bridge or a tiny backend.

## Health Connect facts

- `androidx.health.connect:connect-client`: stable **1.1.0** (2025-10-08), alpha **1.2.0-alpha06** (2026-08-26). Alpha 04 added Matchmaking APIs. Alpha 03 added richer exercise fields.
- Records with no aggregates (read the raw records and average them yourself): HRV RMSSD, VO2 max, body fat, lean mass, SpO2, respiratory rate.
- Records with aggregates:
  - `BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL`
  - `SkinTemperatureRecord.TEMPERATURE_DELTA_AVG/MIN/MAX`, gated by `FEATURE_SKIN_TEMPERATURE`
  - `RestingHeartRateRecord.BPM_*`
  - `SleepSessionRecord.SLEEP_DURATION_TOTAL`
  - `ExerciseSessionRecord.EXERCISE_DURATION_TOTAL`
- Data origin: `record.metadata.dataOrigin.packageName`, plus `metadata.device` and `metadata.recordingMethod`. Filter with `dataOriginFilter`. `AggregationResult.dataOrigins` lists the contributing apps.
- History: by default an app can read only 30 days before its **first** permission grant. `READ_HEALTH_DATA_HISTORY` removes that limit and is gated by `FEATURE_READ_HEALTH_DATA_HISTORY`. On Android 14+, an app's own data has no limit.
- Background reads need `READ_HEALTH_DATA_IN_BACKGROUND` (gated by `FEATURE_READ_HEALTH_DATA_IN_BACKGROUND`), requested while the app is in the foreground.
- Since the June 2026 Health Connect update, phone-counted steps use a per-app Synthetic Package Name instead of `android`.

## Apple Health export

- On iPhone: Health > profile > **Export All Health Data**. This produces a zip containing `export.xml`, `export_cda.xml`, `workout-routes/*.gpx` and `electrocardiograms/*.csv`.
- Use a streaming parser (`XmlPullParser`). Files can be GBs.
- Worth importing: StepCount, HeartRate, RestingHeartRate, VO2Max, BodyMass, BodyFatPercentage (a 0-1 fraction), LeanBodyMass, OxygenSaturation, RespiratoryRate, ActiveEnergyBurned, SleepAnalysis (InBed/Awake/AsleepCore/Deep/REM/Unspecified) and Workout.
- **Apple's HRV is SDNN, not RMSSD.** Never mix it with Health Connect HRV.
- Live alternative: only an iPhone-side exporter such as Health Auto Export (third party, premium) posting JSON somewhere Forge can read.

## Unverified / to check on device

- Exact Health Connect types for Withings and COROS.
- Whether WHOOP writes steps.
- Xiaomi model-specific SpO2 and resting HR.
- Garmin Personal API endpoint list and limits (Swagger docs not reviewed).
- The COROS "MCP with OAuth" route.
- Package names of each vendor app.
