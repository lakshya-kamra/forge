package com.laksh.forge.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.domain.exercise.ExerciseCatalog
import com.laksh.forge.domain.program.LibraryDay
import com.laksh.forge.domain.program.LibraryExercise
import com.laksh.forge.domain.program.LibraryProgram
import com.laksh.forge.domain.program.ProgramSource
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.components.SectionHeader

@Composable
fun ProgramDetailScreen(
    programId: String,
    onBack: () -> Unit,
    onAdopted: () -> Unit,
    onOpenExercise: (String) -> Unit
) {
    val vm: ProgramDetailViewModel = viewModel(
        key = "program_$programId",
        factory = ProgramDetailViewModel.factory(programId)
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showSetup by rememberSaveable { mutableStateOf(false) }
    var week by rememberSaveable { mutableIntStateOf(1) }

    LaunchedEffect(state.adopted) {
        if (state.adopted) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            showSetup = false
            onAdopted()
        }
    }

    val program = state.program
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(program?.name ?: "Program", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = program?.author?.let { author ->
                    @Composable { Text(author, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                scrollBehavior = scroll
            )
        },
        bottomBar = {
            if (program != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            showSetup = true
                        },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .heightIn(min = ButtonDefaults.MediumContainerHeight)
                    ) {
                        Icon(Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start this program", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    ) { padding ->
        when {
            state.loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) { LoadingIndicator(Modifier.size(72.dp)) }

            state.error != null || program == null -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "Couldn't open this program",
                    body = state.error ?: "It isn't in the library.",
                    action = { Pill("Try again", filled = true, onClick = { vm.load() }) }
                )
            }

            else -> DetailContent(
                program = program,
                explanation = state.explanation,
                week = week.coerceIn(1, (program.weeks?.size ?: 1).coerceAtLeast(1)),
                onWeek = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    week = it
                },
                onOpenExercise = { ex ->
                    val key = ex.exerciseId?.takeIf { ExerciseCatalog.get(it) != null }
                        ?: ExerciseCatalog.keyFor(ex.name)
                    onOpenExercise(key)
                },
                padding = padding
            )
        }
    }

    if (showSetup && program != null) {
        AdoptSheet(
            program = program,
            fields = state.fields,
            unit = state.unit,
            hasActiveProgram = state.hasActiveProgram,
            adopting = state.adopting,
            error = state.adoptError,
            onDismiss = { if (!state.adopting) showSetup = false },
            onStart = { values, rounding -> vm.adopt(values, rounding) }
        )
    }
}

@Composable
private fun DetailContent(
    program: LibraryProgram,
    explanation: List<String>,
    week: Int,
    onWeek: (Int) -> Unit,
    onOpenExercise: (LibraryExercise) -> Unit,
    padding: PaddingValues
) {
    val context = LocalContext.current
    val weeks = program.weeks.orEmpty()
    val days: List<LibraryDay> = weeks.getOrNull(week - 1)?.days ?: program.days

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 32.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "overview", contentType = "overview") {
            ForgeCard(Modifier.animateItem()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Pill(categoryLabel(program.category), filled = true, color = categoryColor(program.category))
                    Pill(levelLabel(program.level))
                    Pill(programMeta(program), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                Text(program.summary, style = MaterialTheme.typography.bodyLarge)
                if (program.equipment.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Needs: " + program.equipment.joinToString(", ") { equipmentLabel(it).lowercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (program.isFree) {
                    Spacer(Modifier.height(12.dp))
                    FreeBadge()
                }
            }
        }

        item(key = "evidence", contentType = "text_card") {
            ForgeCard(Modifier.animateItem()) {
                Text("Evidence note", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(program.evidenceNote, style = MaterialTheme.typography.bodyMedium)
            }
        }

        item(key = "progression", contentType = "progression") {
            ProgressionCard(program, explanation, Modifier.animateItem())
        }

        item(key = "schedule", contentType = "text_card") {
            ForgeCard(Modifier.animateItem()) {
                Text("Schedule", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(program.schedule, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (weeks.isNotEmpty()) {
            item(key = "weeks", contentType = "weeks") {
                Column(Modifier.animateItem()) {
                    SectionHeader("WEEKS")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(weeks, key = { "w_${it.week}" }) { w ->
                            FilterChip(
                                selected = w.week == week,
                                onClick = { onWeek(w.week) },
                                label = {
                                    Text(
                                        "Week ${w.week}" + (w.label?.let { " · $it" } ?: ""),
                                        maxLines = 1
                                    )
                                },
                                shape = MaterialTheme.shapes.extraLarge
                            )
                        }
                    }
                }
            }
        }

        item(key = "days_header", contentType = "header") {
            SectionHeader(if (weeks.isNotEmpty()) "WEEK $week DAYS" else "TRAINING DAYS", Modifier.animateItem())
        }
        itemsIndexed(days, key = { i, _ -> "day_${week}_$i" }, contentType = { _, _ -> "day" }) { _, day ->
            DayCard(day = day, onOpenExercise = onOpenExercise, modifier = Modifier.animateItem())
        }

        program.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            item(key = "notes", contentType = "text_card") {
                ForgeCard(Modifier.animateItem()) {
                    Text("Notes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item(key = "sources_header", contentType = "header") {
            SectionHeader("SOURCES", Modifier.animateItem())
        }
        itemsIndexed(program.sources, key = { i, _ -> "src_$i" }, contentType = { _, _ -> "source" }) { _, src ->
            SourceRow(src, onClick = { openUrl(context, src.url) }, modifier = Modifier.animateItem())
        }
        if (program.verifiedAgainst.isNotEmpty()) {
            item(key = "verified", contentType = "footnote") {
                Text(
                    "Sets, reps and rules were checked against ${program.verifiedAgainst.size} " +
                        "source page${if (program.verifiedAgainst.size == 1) "" else "s"} before being added.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .animateItem()
                )
            }
        }
    }
}

@Composable
private fun ProgressionCard(program: LibraryProgram, explanation: List<String>, modifier: Modifier = Modifier) {
    var showAuthor by rememberSaveable { mutableStateOf(false) }
    ForgeCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.TrendingUp, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("How it progresses", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        explanation.forEach { line ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        val rotation by animateFloatAsState(
            if (showAuthor) 180f else 0f,
            MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "authorChevron"
        )
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { showAuthor = !showAuthor }
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "In the author's words",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                Icons.Outlined.ExpandMore, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
        }
        AnimatedVisibility(
            visible = showAuthor,
            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())
        ) {
            Column {
                Text(program.progression.description, style = MaterialTheme.typography.bodySmall)
                program.progression.deload?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Stalls and deloads", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DayCard(day: LibraryDay, onOpenExercise: (LibraryExercise) -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(day.name) { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "dayChevron"
    )
    ForgeCard(modifier = modifier, onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(day.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(day.focus, "${day.exercises.size} exercises").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.graphicsLayer { rotationZ = rotation }
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())
        ) {
            Column(Modifier.padding(top = 10.dp)) {
                day.exercises.forEachIndexed { i, ex ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ExerciseRow(ex, onClick = { onOpenExercise(ex) })
                }
            }
        }
    }
}

@Composable
private fun ExerciseRow(ex: LibraryExercise, onClick: () -> Unit) {
    var showNotes by rememberSaveable(ex.name) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ex.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            if (ex.optional) Pill("Optional", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            describeExercise(ex) + (ex.restSeconds?.takeIf { it > 0 }?.let { " · ${restLabel(it)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ex.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            Spacer(Modifier.height(4.dp))
            Text(
                notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = if (showNotes) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { showNotes = !showNotes }
            )
        }
    }
}

@Composable
private fun SourceRow(src: ProgramSource, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val icon = when (src.kind) {
        "video" -> Icons.Outlined.PlayCircle
        "spreadsheet" -> Icons.Outlined.TableChart
        "book", "wiki" -> Icons.AutoMirrored.Outlined.MenuBook
        else -> Icons.AutoMirrored.Outlined.Article
    }
    val host = runCatching { android.net.Uri.parse(src.url).host?.removePrefix("www.") }.getOrNull()
    ForgeCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(src.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                host?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(
                Icons.AutoMirrored.Outlined.OpenInNew, "Open",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
