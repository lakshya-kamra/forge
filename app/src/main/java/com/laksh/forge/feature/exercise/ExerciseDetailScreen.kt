package com.laksh.forge.feature.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.domain.exercise.ExerciseLabels
import com.laksh.forge.ui.components.ErrorNotice
import com.laksh.forge.ui.components.ExpressiveLoading
import com.laksh.forge.ui.components.ForgeSubScreen
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.ui.skeleton.ExerciseDemo
import kotlinx.coroutines.launch

/**
 * One lift: the 3D demonstration, what it trains, how to do it, tutorials, photos and the
 * user's own history. [exerciseKey] is a catalog id, or a raw name from a program or log
 * that the catalog does not know; the latter still gets history and trusted search links.
 */
@Composable
fun ExerciseDetailScreen(exerciseKey: String, onBack: () -> Unit) {
    val vm: ExerciseDetailViewModel = viewModel(
        key = "exercise_$exerciseKey",
        factory = ExerciseDetailViewModel.factory(exerciseKey)
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val open: (String) -> Unit = { url ->
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        if (!ExerciseLinks.open(context, url)) {
            scope.launch { snackbar.showSnackbar("No app on this phone can open that link.") }
        }
    }

    val info = state.info
    val subtitle = info?.let { i ->
        (i.primaryMuscles.take(2).map(ExerciseLabels::muscle) + i.equipment.take(1).map(ExerciseLabels::equipment))
            .joinToString(" · ")
            .ifBlank { null }
    }

    ForgeSubScreen(
        title = state.title,
        subtitle = subtitle,
        onBack = onBack,
        snackbarHostState = snackbar
    ) { padding ->
        if (state.loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) { ExpressiveLoading(message = "Opening the exercise library") }
            return@ForgeSubScreen
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.error?.let { err ->
                item(key = "error", contentType = "error") {
                    ErrorNotice(err, Modifier.animateItem(), onRetry = vm::load)
                }
            }

            val motion = state.motion
            if (info == null) {
                item(key = "unknown", contentType = "unknown") { NotInLibraryCard(state.key) }
            } else if (motion != null) {
                item(key = "demo", contentType = "demo") {
                    ExerciseDemo(
                        motion = motion,
                        primaryMuscles = info.primaryMuscles,
                        secondaryMuscles = info.secondaryMuscles,
                        fallback = { message -> PhotoHero(info.name, info.images, message) }
                    )
                }
            } else {
                item(key = "photos-hero", contentType = "photos") {
                    PhotoHero(info.name, info.images, "No 3D demonstration for this exercise yet.")
                }
            }

            if (info != null) {
                item(key = "muscles", contentType = "card") { MuscleCard(info) }
                if (info.cues.isNotEmpty()) {
                    item(key = "cues-h", contentType = "header") { SectionHeader("How to do it") }
                    item(key = "cues", contentType = "card") {
                        PointsCard(info.cues, CueIcon, MaterialTheme.colorScheme.primary)
                    }
                }
                if (info.mistakes.isNotEmpty()) {
                    item(key = "mistakes-h", contentType = "header") { SectionHeader("Common mistakes") }
                    item(key = "mistakes", contentType = "card") {
                        PointsCard(info.mistakes, MistakeIcon, MaterialTheme.colorScheme.error)
                    }
                }
                if (state.videos.isNotEmpty()) {
                    item(key = "videos-h", contentType = "header") { SectionHeader("Tutorials") }
                    items(state.videos, key = { "video-${it.video.videoId}" }, contentType = { "video" }) { v ->
                        VideoCard(v, onOpen = { open(v.video.url) }, modifier = Modifier.animateItem())
                    }
                }
                if (motion != null && info.images.isNotEmpty()) {
                    item(key = "photos-h", contentType = "header") { SectionHeader("Photos") }
                    item(key = "photos", contentType = "photos") { PhotoStrip(info.name, info.images) }
                }
            }

            item(key = "history-h", contentType = "header") { SectionHeader("Your history") }
            item(key = "history", contentType = "history") { HistorySection(state) }

            if ((info == null || state.videos.isEmpty()) && state.creators.isNotEmpty()) {
                item(key = "search-h", contentType = "header") { SectionHeader("Find a tutorial") }
                item(key = "search", contentType = "card") {
                    CreatorSearchCard(info?.name ?: state.key, state.creators, open)
                }
            }

            if (info != null && (state.extras != null || state.licence != null)) {
                item(key = "sources-h", contentType = "header") { SectionHeader("Sources") }
                item(key = "sources", contentType = "card") {
                    SourcesCard(state.extras, state.licence, hasMotion = motion != null, onOpen = open)
                }
            }
        }
    }
}
