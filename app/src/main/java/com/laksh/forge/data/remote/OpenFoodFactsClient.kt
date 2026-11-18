package com.laksh.forge.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Nutrition for a scanned product, normalised to per-100g plus an optional serving size. */
data class ProductInfo(
    val barcode: String,
    val name: String,
    val brand: String?,
    val kcalPer100g: Double,
    val proteinPer100g: Double,
    val carbsPer100g: Double,
    val fatPer100g: Double,
    val fiberPer100g: Double,
    val servingSizeG: Double?,
    val servingDesc: String
)

class ProductNotFound(val barcode: String) : Exception("No product found for $barcode")

/**
 * Open Food Facts lookup. Free, no key, and covers most packaged goods; when it misses,
 * the caller falls back to photographing the nutrition label.
 */
class OpenFoodFactsClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun lookup(barcode: String): ProductInfo = withContext(Dispatchers.IO) {
        val url = "https://world.openfoodfacts.org/api/v2/product/$barcode.json" +
            "?fields=product_name,product_name_en,brands,nutriments,serving_size,quantity"

        val request = Request.Builder()
            .url(url)
            // Open Food Facts asks clients to identify themselves.
            .header("User-Agent", "Forge/1.0 (Android; personal fitness tracker)")
            .build()

        val raw = try {
            http.newCall(request).execute().use { r ->
                if (r.code == 404) throw ProductNotFound(barcode)
                if (!r.isSuccessful) throw IOException("Lookup failed (${r.code})")
                r.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw IOException("Could not reach the food database: ${e.message}")
        }

        val root = runCatching { AnthropicClient.JSON.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw IOException("Malformed response from food database") }

        val status = root["status"]?.jsonPrimitive?.content
        if (status == "0" || root["product"] == null) throw ProductNotFound(barcode)

        val product = root["product"]!!.jsonObject
        val nutriments = product["nutriments"]?.jsonObject

        fun n(vararg keys: String): Double {
            for (k in keys) {
                nutriments?.get(k)?.jsonPrimitive?.doubleOrNull?.let { return it }
            }
            return 0.0
        }

        val name = product["product_name_en"]?.jsonPrimitive?.contentOrNullSafe()
            ?: product["product_name"]?.jsonPrimitive?.contentOrNullSafe()
            ?: throw ProductNotFound(barcode)

        // Some entries only carry kJ; convert rather than showing zero.
        var kcal = n("energy-kcal_100g")
        if (kcal <= 0.0) {
            val kj = n("energy-kj_100g", "energy_100g")
            if (kj > 0) kcal = kj / 4.184
        }

        val servingRaw = product["serving_size"]?.jsonPrimitive?.contentOrNullSafe()
        val servingG = servingRaw?.let { parseGrams(it) }

        ProductInfo(
            barcode = barcode,
            name = name.trim(),
            brand = product["brands"]?.jsonPrimitive?.contentOrNullSafe()
                ?.split(",")?.firstOrNull()?.trim(),
            kcalPer100g = kcal,
            proteinPer100g = n("proteins_100g"),
            carbsPer100g = n("carbohydrates_100g"),
            fatPer100g = n("fat_100g"),
            fiberPer100g = n("fiber_100g"),
            servingSizeG = servingG,
            servingDesc = servingRaw ?: "100 g"
        )
    }

    /** Pull a gram figure out of strings like "30 g (2 biscuits)" or "1 cup (240ml)". */
    private fun parseGrams(s: String): Double? {
        val m = Regex("""(\d+(?:[.,]\d+)?)\s*(g|ml)""", RegexOption.IGNORE_CASE).find(s) ?: return null
        return m.groupValues[1].replace(',', '.').toDoubleOrNull()
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        content.takeIf { it.isNotBlank() && it != "null" }
}
