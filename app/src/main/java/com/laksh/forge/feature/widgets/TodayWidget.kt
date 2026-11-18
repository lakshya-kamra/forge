package com.laksh.forge.feature.widgets

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.laksh.forge.data.reminders.DeepLinks
import kotlinx.coroutines.CancellationException
import java.util.Locale
import kotlin.math.abs

/**
 * Home-screen "Today": calories left as a ring, protein as a bar, and a one-tap "Log food"
 * that deep-links straight into the logger. Data is loaded before provideContent so the
 * Glance composition is a pure function of a snapshot.
 */
class TodayWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = try {
            WidgetData.today()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val showRing = snapshot != null && snapshot.onboarded && !snapshot.hideNumbers
        val track = if (showRing) RingBitmaps.track() else null
        val arc = if (showRing) RingBitmaps.arc(snapshot!!.kcalProgress) else null

        provideContent {
            GlanceTheme {
                TodayContent(snapshot, track, arc)
            }
        }
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(240.dp, 110.dp)
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

private fun fmt(n: Int): String = String.format(Locale.getDefault(), "%,d", n)

@Composable
private fun TodayContent(snapshot: TodaySnapshot?, track: Bitmap?, arc: Bitmap?) {
    val context = LocalContext.current
    val wide = LocalSize.current.width >= TodayWidget.WIDE.width
    val colors = GlanceTheme.colors

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(colors.widgetBackground)
            .cornerRadius(24.dp)
            .padding(12.dp)
            .clickable(actionStartActivity(DeepLinks.intent(context, DeepLinks.TODAY))),
        contentAlignment = Alignment.Center
    ) {
        when {
            snapshot == null -> Message(
                title = "Couldn't load today",
                body = "Tap to open Forge."
            )
            !snapshot.onboarded -> Message(
                title = "Set up Forge",
                body = "Finish setup to see today's numbers here."
            )
            snapshot.hideNumbers -> NumberFreeContent(snapshot, wide)
            wide -> WideContent(snapshot, track, arc)
            else -> SmallContent(snapshot, track, arc)
        }
    }
}

@Composable
private fun Message(title: String, body: String) {
    val colors = GlanceTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title,
            style = TextStyle(color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            body,
            style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center),
            maxLines = 3
        )
    }
}

@Composable
private fun Ring(snapshot: TodaySnapshot, track: Bitmap?, arc: Bitmap?, sizeDp: Int) {
    val colors = GlanceTheme.colors
    Box(modifier = GlanceModifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
        if (track != null) {
            Image(
                provider = ImageProvider(track),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(colors.surfaceVariant)
            )
        }
        if (arc != null) {
            Image(
                provider = ImageProvider(arc),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(if (snapshot.over) colors.error else colors.primary)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                fmt(abs(snapshot.remainingKcal)),
                style = TextStyle(
                    color = colors.onSurface,
                    fontSize = if (sizeDp >= 88) 20.sp else 16.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1
            )
            Text(
                if (snapshot.over) "over" else "left",
                style = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp)
            )
        }
    }
}

@Composable
private fun LogButton(compact: Boolean) {
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    Box(
        modifier = GlanceModifier
            .background(colors.primary)
            .cornerRadius(100.dp)
            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = 8.dp)
            .clickable(actionStartActivity(DeepLinks.intent(context, DeepLinks.LOG))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (compact) "+ Log" else "+ Log food",
            style = TextStyle(color = colors.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 1
        )
    }
}

@Composable
private fun SmallContent(snapshot: TodaySnapshot, track: Bitmap?, arc: Bitmap?) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Ring(snapshot, track, arc, sizeDp = 72)
        Spacer(GlanceModifier.height(6.dp))
        LogButton(compact = true)
    }
}

@Composable
private fun WideContent(snapshot: TodaySnapshot, track: Bitmap?, arc: Bitmap?) {
    val colors = GlanceTheme.colors
    Row(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Ring(snapshot, track, arc, sizeDp = 88)
        Spacer(GlanceModifier.width(14.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                "TODAY",
                style = TextStyle(color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            )
            Text(
                // Targets are shown as a range: the guardrails display rule for calorie targets.
                "${fmt(snapshot.kcal)} of ${fmt(snapshot.targetKcal)} ± 100 kcal",
                style = TextStyle(color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1
            )
            Spacer(GlanceModifier.height(8.dp))
            Text(
                "Protein ${fmt(snapshot.proteinG)} / ${fmt(snapshot.targetProteinG)} g",
                style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp),
                maxLines = 1
            )
            Spacer(GlanceModifier.height(4.dp))
            LinearProgressIndicator(
                progress = snapshot.proteinProgress,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                color = colors.secondary,
                backgroundColor = colors.surfaceVariant
            )
            Spacer(GlanceModifier.height(10.dp))
            LogButton(compact = false)
        }
    }
}

/** For people who chose to hide numbers: no calories, no ring - just logging. */
@Composable
private fun NumberFreeContent(snapshot: TodaySnapshot, wide: Boolean) {
    val colors = GlanceTheme.colors
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "TODAY",
            style = TextStyle(color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            when (snapshot.items) {
                0 -> "Nothing logged yet"
                1 -> "1 item logged"
                else -> "${snapshot.items} items logged"
            },
            style = TextStyle(color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            maxLines = 1
        )
        Spacer(GlanceModifier.height(10.dp))
        LogButton(compact = !wide)
    }
}
