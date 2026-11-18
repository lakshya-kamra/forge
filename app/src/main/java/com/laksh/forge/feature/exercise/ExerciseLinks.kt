package com.laksh.forge.feature.exercise

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.domain.NutritionMath
import kotlin.math.abs
import kotlin.math.roundToLong

internal object ExerciseLinks {
    /**
     * Opens a web link with ACTION_VIEW. YouTube watch and channel URLs are verified app links,
     * so they land in the YouTube app when it is installed and in the browser otherwise; this
     * needs no package-visibility queries in the manifest. Returns false if nothing can open it.
     */
    fun open(context: Context, url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

internal object ExerciseFormat {
    /** Weight in the user's unit, with a decimal only when it carries information. */
    fun weight(kg: Double, unit: WeightUnit): String {
        val v = NutritionMath.kgToDisplay(kg, unit)
        val tenths = (v * 10).roundToLong()
        val text = if (tenths % 10 == 0L) (tenths / 10).toString() else "%.1f".format(v)
        return "$text ${unit.label}"
    }

    fun number(v: Double): String {
        val tenths = (v * 10).roundToLong()
        return if (abs(tenths % 10) == 0L) (tenths / 10).toString() else "%.1f".format(v)
    }
}
