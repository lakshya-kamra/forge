package com.laksh.forge.feature.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.laksh.forge.data.reminders.DeepLinks
import kotlinx.coroutines.CancellationException

/**
 * Home-screen "Next workout": the next day of the active program (or the session already in
 * progress), what's in it, and when this program was last trained. Tapping opens Train, or
 * the open session directly.
 */
class NextWorkoutWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = try {
            WidgetData.nextWorkout()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        provideContent {
            GlanceTheme {
                NextWorkoutContent(snapshot)
            }
        }
    }

    companion object {
        val COMPACT = DpSize(180.dp, 110.dp)
        val TALL = DpSize(180.dp, 180.dp)
    }
}

class NextWorkoutWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextWorkoutWidget()
}

@Composable
private fun NextWorkoutContent(s: NextWorkoutSnapshot?) {
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    val tall = LocalSize.current.height >= NextWorkoutWidget.TALL.height
    val target = when {
        s?.openSessionId != null -> DeepLinks.session(s.openSessionId)
        else -> DeepLinks.TRAIN
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(colors.widgetBackground)
            .cornerRadius(24.dp)
            .padding(14.dp)
            .clickable(actionStartActivity(DeepLinks.intent(context, target))),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            s == null -> {
                Kicker("NEXT WORKOUT")
                Headline("Couldn't load your program")
                Detail("Tap to open Forge.")
            }

            s.openSessionId != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = GlanceModifier
                            .background(colors.primaryContainer)
                            .cornerRadius(100.dp)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            "IN PROGRESS",
                            style = TextStyle(color = colors.onPrimaryContainer, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        )
                    }
                }
                Spacer(GlanceModifier.height(6.dp))
                Headline(s.openSessionTitle ?: "Session")
                Detail("Tap to get back to it.")
            }

            s.programName == null -> {
                Kicker("NEXT WORKOUT")
                Headline("No active program")
                Detail("Pick one from the library in Train.")
            }

            s.dayName == null -> {
                Kicker(s.programName.orEmpty().uppercase())
                Headline("This program has no days yet")
                Detail("Open Train to set it up.")
            }

            else -> {
                Kicker(if (s.trainedToday) "TRAINED TODAY · UP NEXT" else "NEXT WORKOUT")
                Headline(s.dayName.orEmpty())
                val sub = listOfNotNull(
                    s.programName,
                    s.dayFocus?.takeIf { it.isNotBlank() },
                    when (s.exerciseCount) {
                        0 -> null
                        1 -> "1 exercise"
                        else -> "${s.exerciseCount} exercises"
                    }
                ).joinToString(" · ")
                Detail(sub)
                if (tall && s.exercisePreview.isNotEmpty()) {
                    Spacer(GlanceModifier.height(8.dp))
                    s.exercisePreview.forEach { name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = GlanceModifier
                                    .width(4.dp)
                                    .height(4.dp)
                                    .background(colors.primary)
                                    .cornerRadius(2.dp)
                            ) {}
                            Spacer(GlanceModifier.width(6.dp))
                            Text(
                                name,
                                style = TextStyle(color = colors.onSurface, fontSize = 12.sp),
                                maxLines = 1
                            )
                        }
                    }
                    if (s.exerciseCount > s.exercisePreview.size) {
                        Detail("+${s.exerciseCount - s.exercisePreview.size} more")
                    }
                }
                s.lastTrainedLabel?.let {
                    Spacer(GlanceModifier.height(6.dp))
                    Detail("Last trained: $it")
                }
            }
        }
    }
}

@Composable
private fun Kicker(text: String) {
    Text(
        text,
        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold),
        maxLines = 1
    )
}

@Composable
private fun Headline(text: String) {
    Text(
        text,
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold),
        maxLines = 2
    )
}

@Composable
private fun Detail(text: String) {
    Text(
        text,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
        maxLines = 2
    )
}
