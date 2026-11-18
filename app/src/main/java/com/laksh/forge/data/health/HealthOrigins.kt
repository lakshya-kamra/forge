package com.laksh.forge.data.health

import android.content.Context
import androidx.health.connect.client.records.metadata.Device
import java.util.concurrent.ConcurrentHashMap

/**
 * How much a value's source is trusted when two sources report the same field for the same
 * day. A dedicated wearable (strap, ring, watch, scale) measures the body directly; the phone
 * only infers. Stored as an Int in the layer files, so never renumber these.
 */
enum class SourceRank(val value: Int) {
    PHONE(1),
    DEVICE(2);

    companion object {
        fun of(value: Int): SourceRank = if (value >= DEVICE.value) DEVICE else PHONE
    }
}

/** What Forge knows about one data origin (an app package, or one of Forge's direct sources). */
data class OriginInfo(
    val id: String,
    /** Human label: the vendor app name, or the installed app's own label, or the raw id. */
    val label: String,
    /** Brand id from the wearables catalog (feature/wearables/BrandCatalog), or null. */
    val brandId: String?,
    /** True for vendor apps whose only job is relaying a dedicated device. */
    val dedicatedDevice: Boolean
)

/**
 * Maps Health Connect data origins (package names) to brands.
 *
 * Package ids come from docs/research/wearables.json: Play Store listing ids and the Google Play
 * Health Connect collection. The research flags Garmin's as unverified. Brands whose package was
 * never verified (Google Health/Fitbit, Oura, COROS) are matched by the installed app's label
 * instead, which is a display heuristic only - it never changes what data is read.
 */
object HealthOrigins {
    /** Origin id used for values fetched straight from the WHOOP Developer API. */
    const val WHOOP_API = "whoop"

    /** Origin id used for values imported from an Apple Health export.zip. */
    const val APPLE_EXPORT = "apple_health"

    const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

    private data class Known(val brandId: String?, val label: String, val dedicatedDevice: Boolean)

    private val known: Map<String, Known> = mapOf(
        "com.whoop.android" to Known("whoop", "WHOOP", true),
        "com.huami.watch.hmwatchmanager" to Known("amazfit", "Zepp (Amazfit)", true),
        "com.sec.android.app.shealth" to Known("samsung", "Samsung Health", true),
        "com.garmin.android.apps.connectmobile" to Known("garmin", "Garmin Connect", true),
        "fi.polar.polarflow" to Known("polar", "Polar Flow", true),
        "com.withings.wiscale2" to Known("withings", "Withings", true),
        "com.xiaomi.wearable" to Known("xiaomi", "Mi Fitness", true),
        HEALTH_CONNECT_PACKAGE to Known(null, "Health Connect", false)
    )

    /** Label keywords for brands whose Android package id is not in the research. */
    private val labelKeywords: List<Pair<String, List<String>>> = listOf(
        "google" to listOf("fitbit", "google health", "pixel watch"),
        "oura" to listOf("oura"),
        "coros" to listOf("coros")
    )

    private val labelCache = ConcurrentHashMap<String, String>()

    fun describe(context: Context?, id: String): OriginInfo {
        when (id) {
            WHOOP_API -> return OriginInfo(id, "WHOOP (direct API)", "whoop", true)
            APPLE_EXPORT -> return OriginInfo(id, "Apple Health export", "apple", false)
        }
        known[id]?.let { return OriginInfo(id, it.label, it.brandId, it.dedicatedDevice) }

        val label = appLabel(context, id)
        val lower = label.lowercase()
        val brand = labelKeywords.firstOrNull { (_, words) -> words.any { it in lower } }?.first
        return OriginInfo(id, label, brand, dedicatedDevice = brand != null)
    }

    /**
     * Rank for a value. A record's own device type wins when the writer filled it in; otherwise
     * fall back to whether the writing app exists only to relay a wearable.
     */
    fun rank(info: OriginInfo, deviceType: Int? = null): SourceRank = when (deviceType) {
        Device.TYPE_PHONE -> SourceRank.PHONE
        Device.TYPE_WATCH,
        Device.TYPE_RING,
        Device.TYPE_FITNESS_BAND,
        Device.TYPE_CHEST_STRAP,
        Device.TYPE_SCALE -> SourceRank.DEVICE
        else -> if (info.dedicatedDevice) SourceRank.DEVICE else SourceRank.PHONE
    }

    /**
     * Resolves an installed app's label. Health Connect writers declare the permissions-rationale
     * intent that Forge's manifest already queries for, so they are usually visible to us; if not,
     * the package name itself is shown rather than guessing.
     */
    @Suppress("DEPRECATION")
    private fun appLabel(context: Context?, pkg: String): String {
        labelCache[pkg]?.let { return it }
        if (context == null) return pkg
        val label = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
        labelCache[pkg] = label
        return label
    }
}
