package com.laksh.forge.feature.wearables

/** How a brand's data can reach this Android phone, per docs/research/wearables.json. */
enum class BrandRoute { HEALTH_CONNECT, BRIDGE_APP, NOT_POSSIBLE }

/**
 * One wearable brand as the research describes it. Every line of text here is a paraphrase of
 * docs/research/wearables.json (howToEnable, types, notWritten, caveats); where the research marks
 * something unverified, the text says so.
 */
data class Brand(
    val id: String,
    val name: String,
    val devices: String,
    val route: BrandRoute,
    /** What reaches Health Connect (or why nothing does). */
    val gives: String,
    /** What the research says this brand does not share, or null. */
    val missing: String?,
    val steps: List<String>,
    val caveat: String?
)

object BrandCatalog {

    val brands: List<Brand> = listOf(
        Brand(
            id = "whoop",
            name = "WHOOP",
            devices = "WHOOP 4.0, WHOOP 5.0 / MG",
            route = BrandRoute.HEALTH_CONNECT,
            gives = "Via Health Connect: sleep, workouts, calories, steps, resting heart rate, respiratory rate and blood oxygen.",
            missing = "Recovery, HRV, strain and skin temperature never reach Health Connect - connect the WHOOP API below for those.",
            steps = listOf(
                "In the WHOOP app, open its Health Connect integration (offered during onboarding, or later in the app's integration settings).",
                "In Health Connect choose Allow all, or pick data types, then tap Set up and Allow.",
                "Reinstalling WHOOP removes these permissions, so turn it on again after a reinstall."
            ),
            caveat = "According to WHOOP, resting heart rate and respiratory rate need the Peak tier."
        ),
        Brand(
            id = "amazfit",
            name = "Amazfit / Zepp",
            devices = "Balance, Active, GTR/GTS 4 and later, T-Rex, Cheetah, Helio Ring/Strap",
            route = BrandRoute.HEALTH_CONNECT,
            gives = "Steps, distance, calories, sleep, workouts, heart rate, resting heart rate, weight, respiratory rate, VO2 max and blood oxygen.",
            missing = "HRV and skin temperature aren't shared.",
            steps = listOf(
                "Open Zepp → Profile → Link third-party accounts.",
                "Choose Health Connect, authorize, and pick all data or specific types.",
                "On Android 14 and later Health Connect is built into the system - nothing extra to install."
            ),
            caveat = "Some older models (Bip 3, GTR 3, GTS 2) reportedly can't sync this way (unverified)."
        ),
        Brand(
            id = "google",
            name = "Pixel Watch / Fitbit",
            devices = "Pixel Watch 1-4; Fitbit Charge, Inspire, Versa, Sense, Luxe",
            route = BrandRoute.HEALTH_CONNECT,
            gives = "The richest set: steps, heart rate, HRV (RMSSD), resting heart rate, sleep stages, workouts, total calories, VO2 max, respiratory rate, skin temperature, weight and body fat.",
            missing = "Blood oxygen and active calories are read-only for the Google Health app, so they aren't written.",
            steps = listOf(
                "Open the Google Health app (formerly Fitbit) → Connections → Partner apps → Add connections.",
                "Choose to sync with Health Connect → Set up, then choose the data.",
                "Also allow historic-data and background access, then tap Done.",
                "Check Connections → Partner apps → Manage Health Connect: it should say Synced with a time."
            ),
            caveat = "Needs Android 9 or later and an adult Google Account."
        ),
        Brand(
            id = "samsung",
            name = "Samsung Galaxy Watch",
            devices = "Galaxy Watch 4 and later, Galaxy Ring, Galaxy Fit",
            route = BrandRoute.HEALTH_CONNECT,
            gives = "Steps, heart rate, sleep stages, workouts, calories, distance, blood oxygen, VO2 max (from exercise), weight and body fat.",
            missing = "Resting heart rate, HRV, skin temperature and respiratory rate aren't shared.",
            steps = listOf(
                "Open Samsung Health → ⋮ menu → Settings → Health Connect → Get started.",
                "Choose the data types; read and write access are granted separately.",
                "Check under Settings → Health Connect → Data and access → Browse data.",
                "Keep Samsung Health and the Galaxy Wearable plugin up to date, with Sync with Samsung Cloud on."
            ),
            caveat = "This menu path wasn't re-checked against the current Samsung Health app. Whether every watch feature works on a non-Samsung phone is unverified."
        ),
        Brand(
            id = "garmin",
            name = "Garmin",
            devices = "Forerunner, Fenix, Epix, Venu, Vivoactive, Instinct, Edge, Index scale",
            route = BrandRoute.HEALTH_CONNECT,
            gives = "Calories, distance, heart rate, steps, sleep stages, floors, weight and body fat.",
            missing = "HRV status, VO2 max, blood oxygen, respiratory rate, skin temperature and Body Battery aren't shared.",
            steps = listOf(
                "Garmin's sharing needs Android 14 or later (this phone qualifies).",
                "Garmin gives no in-app menu path; setup varies by phone - follow Android's \"Get started with Health Connect\" help.",
                "Once on, Garmin Connect writes to Health Connect after each successful watch sync. It's one-way."
            ),
            caveat = "Resting heart rate and workout summaries are seen by Google Health but not listed in Garmin's own FAQ (unverified)."
        ),
        Brand(
            id = "oura",
            name = "Oura",
            devices = "Oura Ring Gen3, Oura Ring 4",
            route = BrandRoute.HEALTH_CONNECT,
            gives = "Heart rate, HRV (RMSSD), sleep stages, steps, distance, calories, workouts, height and weight.",
            missing = "Readiness and sleep scores, blood oxygen, temperature, VO2 max and respiratory rate aren't shared.",
            steps = listOf(
                "Open Oura → menu → Settings → Health Connect.",
                "Choose which data types Oura may read and write."
            ),
            caveat = null
        ),
        Brand(
            id = "polar",
            name = "Polar",
            devices = "Vantage, Grit X, Pacer, Ignite, Polar Loop, H10",
            route = BrandRoute.HEALTH_CONNECT,
            gives = "Workouts (with laps), heart rate during workouts, sleep stages, steps, calories, distance, blood oxygen, weight, resting heart rate and VO2 max.",
            missing = "Nightly Recharge HRV, skin temperature and respiratory rate aren't shared.",
            steps = listOf(
                "Open Polar Flow → General settings → turn Health Connect on.",
                "Choose the data and tap Allow. Use Manage permissions to change it later."
            ),
            caveat = "Resting heart rate and VO2 max come from Polar's physical settings, so they may be values you typed in rather than measurements."
        ),
        Brand(
            id = "coros",
            name = "COROS",
            devices = "Pace, Apex, Vertix, Dura",
            route = BrandRoute.HEALTH_CONNECT,
            gives = "COROS lists Health Connect as supported but doesn't publish which data types it shares.",
            missing = null,
            steps = listOf(
                "In the COROS app: Profile → Settings → 3rd Party Apps → Data Sync, then choose types. (This path is documented for Apple Health; for Health Connect it's assumed similar - unverified.)",
                "Check Health Connect → App permissions → COROS to see what it actually writes."
            ),
            caveat = null
        ),
        Brand(
            id = "withings",
            name = "Withings",
            devices = "ScanWatch, Body/Body+/Body Comp/Body Scan scales, BPM cuffs, Sleep Analyzer",
            route = BrandRoute.HEALTH_CONNECT,
            gives = "Withings exports to Health Connect but doesn't list the types; expect weight and body composition (unverified).",
            missing = null,
            steps = listOf(
                "Open Withings → Profile → Settings (gear) → Export health data to Health Connect → Next.",
                "In the phone's settings: Edit permissions → Withings → turn on the categories → Allow → Done.",
                "Check Health Connect's permission screen to see what's actually shared."
            ),
            caveat = null
        ),
        Brand(
            id = "xiaomi",
            name = "Xiaomi Mi Fitness",
            devices = "Smart Band 8/9/10, Redmi Watch, Xiaomi Watch S",
            route = BrandRoute.HEALTH_CONNECT,
            gives = "Steps, distance, calories, sleep, workouts, heart rate during exercise and weight.",
            missing = "HRV, respiratory rate, blood oxygen, skin temperature and VO2 max aren't shared.",
            steps = listOf(
                "Open Mi Fitness → Profile → Third-party data → Health Connect, enable it and pick types.",
                "Mi Fitness cloud sync must be on, and the band must sync to the phone over Bluetooth first."
            ),
            caveat = "Zepp Life (older Mi Bands) is a different app."
        ),
        Brand(
            id = "huawei",
            name = "Huawei",
            devices = "Watch GT, Watch, Fit, Band (Huawei Health)",
            route = BrandRoute.BRIDGE_APP,
            gives = "Huawei Health doesn't write to Health Connect.",
            missing = null,
            steps = listOf(
                "The only route is the third-party, paid Health Sync app (healthsync.app), which lists Huawei Health as a source and Health Connect as a destination.",
                "Once Health Sync writes to Health Connect, Forge reads it like any other source.",
                "Expect the data to be partial and delayed."
            ),
            caveat = "No official Huawei statement either way was found."
        ),
        Brand(
            id = "apple",
            name = "Apple Watch",
            devices = "Apple Watch (all models), iPhone Health app",
            route = BrandRoute.NOT_POSSIBLE,
            gives = "Apple Watch pairs only with an iPhone, there's no Apple Health app for Android, and HealthKit has no cloud API - so nothing can sync live.",
            missing = null,
            steps = listOf(
                "You can bring history over with an iPhone: Health app → Summary → tap your profile picture → Export All Health Data.",
                "Share the zip somewhere this phone can open it (e.g. Google Drive), then use Import Apple Health below.",
                "Data is only as fresh as the export, and Apple's HRV is SDNN, which Forge keeps separate from RMSSD."
            ),
            caveat = null
        )
    )

    fun byId(id: String): Brand? = brands.firstOrNull { it.id == id }
}
