package com.laksh.forge.feature.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Glance has no arc or ring primitive, so the calorie ring is drawn into bitmaps. They are
 * drawn in plain white and tinted in the widget with GlanceTheme colours, so the ring still
 * follows the wallpaper's dynamic colours and light/dark mode.
 */
object RingBitmaps {

    /** Big enough to stay crisp at ~96 dp on a 3x-density panel, small enough for RemoteViews. */
    private const val SIZE_PX = 288
    private const val STROKE_PX = 30f

    private fun paint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_PX
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private fun bounds(): RectF {
        val inset = STROKE_PX / 2f + 1f
        return RectF(inset, inset, SIZE_PX - inset, SIZE_PX - inset)
    }

    fun track(): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawArc(bounds(), 0f, 360f, false, paint())
        return bmp
    }

    /** An arc from 12 o'clock clockwise covering [progress] (0..1) of the circle. */
    fun arc(progress: Float): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val p = progress.coerceIn(0f, 1f)
        if (p > 0f) {
            // A round cap on a near-zero sweep still draws a dot, which reads as "started".
            Canvas(bmp).drawArc(bounds(), -90f, (360f * p).coerceAtLeast(0.5f), false, paint())
        }
        return bmp
    }
}
