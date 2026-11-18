package com.laksh.forge.feature.onboarding

import com.laksh.forge.feature.settings.AiSettingsScreen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.domain.plan.Pose
import com.laksh.forge.feature.progress.PoseCaptureScreen
import kotlinx.coroutines.delay

/**
 * Onboarding: welcome -> basics -> training -> health & safety -> measurements -> photos ->
 * analysis -> recommendation -> plan review -> accept. Used both as the first-run root and
 * from Routes.ONBOARDING for "Re-plan" / "Redo onboarding".
 */
@Composable
fun OnboardingFlow(onDone: () -> Unit, onOpenProgram: ((String) -> Unit)? = null) {
    val vm: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val currentOnDone by rememberUpdatedState(onDone)
    var capturing by remember { mutableStateOf<List<Pose>?>(null) }

    LaunchedEffect(state.done) {
        if (state.done) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            // Let the success state land before leaving; at first run the shell swaps on its own.
            delay(1100)
            currentOnDone()
        }
    }

    BackHandler(enabled = state.connectingAi) { vm.closeAiSetup() }

    BackHandler(enabled = capturing == null && !state.connectingAi && state.stepIndex > 0 && !state.saving && !state.done) {
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        vm.back()
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (!state.loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator(Modifier.size(72.dp))
            }
            return@Surface
        }

        if (state.connectingAi) {
            // The same screen as Settings > AI, so detection, probing and Test behave identically.
            AiSettingsScreen(onBack = vm::closeAiSetup)
            return@Surface
        }

        val poses = capturing
        if (poses != null) {
            PoseCaptureScreen(
                poses = poses,
                onCaptured = { pose, uri -> vm.addPhoto(pose, uri) },
                onClose = { capturing = null }
            )
            return@Surface
        }

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            FlowTopBar(state, onBack = { vm.back() })
            val motion = MaterialTheme.motionScheme
            AnimatedContent(
                targetState = state.step,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(motion.defaultSpatialSpec()) { w -> dir * w / 3 } + fadeIn(motion.defaultEffectsSpec()))
                        .togetherWith(slideOutHorizontally(motion.fastSpatialSpec()) { w -> -dir * w / 4 } + fadeOut(motion.fastEffectsSpec()))
                        .using(SizeTransform(clip = false))
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "onboardingStep"
            ) { step ->
                StepContent(
                    step = step,
                    state = state,
                    vm = vm,
                    onCapture = { capturing = it },
                    onOpenProgram = onOpenProgram
                )
            }
            FlowBottomBar(state, vm)
        }
    }
}

@Composable
private fun FlowTopBar(state: OnboardingUiState, onBack: () -> Unit) {
    val motion = MaterialTheme.motionScheme
    val steps = state.steps
    val target = (state.stepIndex + 1).toFloat() / steps.size
    val progress by animateFloatAsState(target, motion.slowSpatialSpec(), label = "onboardingProgress")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val canGoBack = state.stepIndex > 0 && !state.saving && !state.done
        IconButton(onClick = onBack, enabled = canGoBack) {
            if (canGoBack) Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
        }
        LinearWavyProgressIndicator(
            progress = { progress },
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "${state.stepIndex + 1}/${steps.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FlowBottomBar(state: OnboardingUiState, vm: OnboardingViewModel) {
    val haptics = LocalHapticFeedback.current
    val d = state.draft
    val (label, enabled) = when (state.step) {
        OnboardingStep.WELCOME -> (if (state.isRedo) "Start re-plan" else "Let's start") to true
        OnboardingStep.PHOTOS -> (if (d.photos.isNotEmpty()) "Continue" else "Continue without photos") to !state.photoBusy
        OnboardingStep.ANALYSIS -> "See my recommendation" to !state.analysing
        OnboardingStep.RECOMMENDATION -> "Review the plan" to (state.proposal != null && !state.planning)
        OnboardingStep.REVIEW -> "Looks good" to (state.proposal != null)
        OnboardingStep.ACCEPT -> (if (state.done) "All set" else "Start my plan") to (!state.saving && !state.done && state.proposal != null)
        else -> "Continue" to true
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.step == OnboardingStep.PHOTOS && d.photos.isNotEmpty()) {
            TextButton(onClick = {
                d.photos.keys.toList().forEach { vm.removePhoto(it) }
                vm.next()
            }) { Text("Skip photos") }
        }
        Button(
            onClick = {
                if (state.step == OnboardingStep.ACCEPT) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    vm.accept()
                } else {
                    val moved = vm.next()
                    haptics.performHapticFeedback(if (moved) HapticFeedbackType.SegmentTick else HapticFeedbackType.Reject)
                }
            },
            enabled = enabled,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight)
        ) {
            if (state.saving) {
                LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp))
            }
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
