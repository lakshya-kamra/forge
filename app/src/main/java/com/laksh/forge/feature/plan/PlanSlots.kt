package com.laksh.forge.feature.plan

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.domain.plan.PhaseCopy
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.util.Dates

/**
 * Today's plan card: the active plan at a glance, or - when there is none, including v1
 * users who never did the v2 onboarding - an invitation to build one. [onOpen] goes to the
 * plan screen, whose empty state offers the re-plan flow.
 */
@Composable
fun PlanSummaryCard(modifier: Modifier = Modifier, onOpen: () -> Unit = {}) {
    val vm: PlanSummaryViewModel = viewModel(factory = PlanSummaryViewModel.Factory)
    val summary by vm.state.collectAsStateWithLifecycle()
    val motion = MaterialTheme.motionScheme

    ForgeCard(modifier = modifier, onClick = onOpen) {
        AnimatedContent(
            targetState = summary,
            contentKey = { it::class },
            transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
            label = "planSummary"
        ) { s ->
            when (s) {
                PlanSummary.Loading -> Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator(Modifier.size(36.dp))
                }
                PlanSummary.None -> InviteContent()
                is PlanSummary.Active -> ActiveContent(s)
            }
        }
    }
}

@Composable
private fun InviteContent() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(52.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Build your personalised plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "A few questions, a tape measure and optional photos - then a phase, targets and the reasons behind them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ActiveContent(s: PlanSummary.Active) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("YOUR PLAN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(PhaseCopy.name(s.phase), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Day ${s.daysIn} of ${s.totalDays}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        val fraction = (s.daysIn.toFloat() / s.totalDays.coerceAtLeast(1)).coerceIn(0f, 1f)
        LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (s.hideNumbers) "Protein ${s.proteinG} g a day" else "${s.kcal} kcal · ${s.proteinG} g protein",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (s.checkinDue) {
                Pill("Check-in due", filled = true, icon = Icons.Outlined.EventAvailable)
            } else {
                Text(
                    "Check-in ${Dates.dayLabel(s.nextCheckin)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
