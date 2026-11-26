package com.laksh.forge.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.data.ai.AiKeys
import com.laksh.forge.data.ai.CommandCodeProvider
import com.laksh.forge.data.ai.CostEstimator
import com.laksh.forge.data.ai.KeyDetection
import com.laksh.forge.data.ai.KeyDetector
import com.laksh.forge.data.ai.ModelOverrides
import com.laksh.forge.data.ai.ModelRouter
import com.laksh.forge.data.ai.ProviderKind
import com.laksh.forge.data.ai.ProviderRegistry
import com.laksh.forge.data.ai.ProviderSpec
import com.laksh.forge.data.ai.TrainingRisk
import com.laksh.forge.data.ai.label
import com.laksh.forge.data.remote.CostTier
import com.laksh.forge.data.remote.Task
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.components.SectionHeader
import java.time.LocalDate

/**
 * "Make it any key": paste a key from any supported LLM provider, see which provider Forge
 * detected, choose a cost tier with an honest monthly estimate, and override models per job.
 */
@Composable
fun AiSettingsScreen(onBack: () -> Unit) {
    val vm: AiSettingsViewModel = viewModel(factory = AiSettingsViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val hapticsOn by rememberUpdatedState(state.settings.haptics)

    // The draft key lives only in memory: never in saved-instance state, which can be
    // written to disk when the process is killed.
    var draftKey by remember { mutableStateOf("") }
    var editingKey by remember { mutableStateOf(false) }
    var choice by rememberSaveable { mutableStateOf(AiKeys.AUTO) }
    var draftBaseUrl by rememberSaveable { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var pickerFor by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            if (event == AiSettingsEvent.KeySaved) {
                draftKey = ""
                editingKey = false
                choice = AiKeys.AUTO
            }
            if (hapticsOn) {
                haptics.performHapticFeedback(
                    if (event == AiSettingsEvent.Failed) HapticFeedbackType.Reject else HapticFeedbackType.Confirm
                )
            }
        }
    }

    val detection = remember(draftKey) { KeyDetector.detect(draftKey) }
    // The provider the privacy note talks about: what the user is about to save, else what's saved.
    val previewProvider: ProviderSpec? = remember(choice, detection, state.provider) {
        ProviderRegistry.byId(choice)
            ?: (detection as? KeyDetection.Match)?.provider
            ?: (detection as? KeyDetection.Rejected)?.provider
            ?: state.provider
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val background = MaterialTheme.colorScheme.background

    Column(
        Modifier
            .fillMaxSize()
            .background(background)
            .imePadding()
    ) {
        LargeFlexibleTopAppBar(
            title = { Text("AI provider") },
            subtitle = {
                Text(
                    when {
                        state.loading -> "Loading..."
                        else -> state.provider?.let { "Using ${it.shortName}" } ?: "Not set up"
                    }
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            },
            // The app scaffold already applies the status-bar inset.
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = background,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            scrollBehavior = scrollBehavior
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "key", contentType = "card") {
                KeyCard(
                    state = state,
                    draftKey = draftKey,
                    onDraftKey = { draftKey = it },
                    detection = detection,
                    choice = choice,
                    onChoice = { choice = it },
                    draftBaseUrl = draftBaseUrl,
                    onDraftBaseUrl = { draftBaseUrl = it },
                    // Hold the editor back until we know whether a key is stored, so it doesn't flash.
                    editing = editingKey || (!state.loading && !state.hasKey),
                    onStartEditing = { editingKey = true },
                    onCancelEditing = {
                        editingKey = false
                        draftKey = ""
                        choice = AiKeys.AUTO
                    },
                    onSave = { vm.save(draftKey, choice, draftBaseUrl) },
                    onTest = vm::test,
                    onRemove = { confirmRemove = true },
                    onDismissStatus = vm::dismissStatus,
                    modifier = Modifier.animateItem()
                )
            }

            if (previewProvider != null) {
                item(key = "privacy-header", contentType = "header") {
                    SectionHeader("What ${previewProvider.shortName} does with your data", Modifier.animateItem())
                }
                item(key = "privacy", contentType = "card") {
                    PrivacyCard(previewProvider, Modifier.animateItem())
                }
            }

            item(key = "tier-header", contentType = "header") {
                SectionHeader("Cost and models", Modifier.animateItem())
            }
            item(key = "tier", contentType = "card") {
                val provider = state.provider
                if (provider == null) {
                    ForgeCard(Modifier.animateItem()) {
                        EmptyState(
                            icon = Icons.Rounded.AutoAwesome,
                            title = if (state.loading) "Loading..." else "No provider yet",
                            body = state.providerError
                                ?: "Add a key above to see which model each job uses and what it costs a month."
                        )
                    }
                } else {
                    TierCard(
                        state = state,
                        onTier = { tier ->
                            if (hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            vm.setTier(tier)
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            val provider = state.provider
            if (provider != null) {
                item(key = "advanced-header", contentType = "header") {
                    AdvancedHeader(
                        open = advancedOpen,
                        onToggle = { advancedOpen = !advancedOpen },
                        modifier = Modifier.animateItem()
                    )
                }
                if (advancedOpen) {
                    val keys = buildList {
                        add(ModelOverrides.DEFAULT_KEY)
                        AiSettingsViewModel.SETTINGS_TASKS.forEach { add(it.name) }
                    }
                    items(keys, key = { "override-$it" }, contentType = { "override" }) { taskKey ->
                        OverrideRow(
                            taskKey = taskKey,
                            state = state,
                            onClick = {
                                pickerFor = taskKey
                                vm.loadModels()
                            },
                            modifier = Modifier.animateItem()
                        )
                    }
                    if (provider.allowsBaseUrlOverride) {
                        item(key = "base-url", contentType = "card") {
                            BaseUrlCard(
                                provider = provider,
                                current = state.settings.customBaseUrl,
                                busy = state.busy != null,
                                onSave = vm::setBaseUrl,
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }

            item(key = "footer", contentType = "footer") {
                Text(
                    "Photos are only sent to the provider when you ask for an estimate on that screen. " +
                        "Your key is encrypted with a key held in this phone's secure hardware and is never backed up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                        .animateItem()
                )
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
            title = { Text("Remove the key?") },
            text = {
                Text(
                    "The key is deleted from this phone. Photo estimates, label reading, program import and " +
                        "the coach stop working until you add a key again. The key itself stays valid with " +
                        "the provider; revoke it in their console if you no longer need it."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    vm.remove()
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            }
        )
    }

    val pickerKey = pickerFor
    val pickerProvider = state.provider
    if (pickerKey != null && pickerProvider != null) {
        ModelPickerSheet(
            taskKey = pickerKey,
            provider = pickerProvider,
            state = state,
            onRetry = { vm.loadModels(force = true) },
            onPick = { model ->
                if (hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                vm.setOverride(pickerKey, model)
                pickerFor = null
            },
            onDismiss = { pickerFor = null }
        )
    }
}

// =====================================================================================
// Key card
// =====================================================================================

@Composable
private fun KeyCard(
    state: AiSettingsUiState,
    draftKey: String,
    onDraftKey: (String) -> Unit,
    detection: KeyDetection,
    choice: String,
    onChoice: (String) -> Unit,
    draftBaseUrl: String,
    onDraftBaseUrl: (String) -> Unit,
    editing: Boolean,
    onStartEditing: () -> Unit,
    onCancelEditing: () -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
    onDismissStatus: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showKey by remember { mutableStateOf(false) }
    val busy = state.busy
    val motion = MaterialTheme.motionScheme

    ForgeCard(modifier = modifier, contentPadding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Key, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "API key",
                    style = MaterialTheme.typography.titleMediumEmphasized
                )
                Text(
                    when {
                        state.loading -> "Checking this phone..."
                        state.savedKeyMask != null -> "Saved and encrypted · ${state.savedKeyMask}"
                        else -> "Paste a key from any supported provider"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = if (state.savedKeyMask != null) FontFamily.Monospace else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (state.hasKey && !editing) {
            Spacer(Modifier.height(14.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.provider?.let {
                    Pill(it.name, filled = true, icon = Icons.Rounded.CheckCircle)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(onClick = onTest, enabled = busy == null) {
                    if (busy == AiBusy.TESTING) {
                        LoadingIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (busy == AiBusy.TESTING) "Testing" else "Test")
                }
                TextButton(onClick = onStartEditing, enabled = busy == null, shapes = ButtonDefaults.shapes()) {
                    Text("Replace")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRemove, enabled = busy == null, shapes = ButtonDefaults.shapes()) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        AnimatedVisibility(
            visible = editing,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec())
        ) {
            Column {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = draftKey,
                    onValueChange = onDraftKey,
                    label = { Text("Any LLM API key") },
                    placeholder = { Text("sk-..., AIza..., gsk_..., xai-...") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showKey) "Hide key" else "Show key"
                            )
                        }
                    },
                    isError = detection is KeyDetection.Rejected,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                DetectionLine(detection = detection, choice = choice)

                Spacer(Modifier.height(16.dp))
                Text(
                    "Provider",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ProviderChip("Auto-detect", selected = choice == AiKeys.AUTO) { onChoice(AiKeys.AUTO) }
                    // An unrecognised key most likely belongs to a provider with no published
                    // format, so those move to the front where they're seen first.
                    val ordered = if (detection is KeyDetection.Unknown) {
                        ProviderRegistry.all.sortedBy { if (it.keyPatterns.isEmpty() && !it.isCustom) 0 else 1 }
                    } else ProviderRegistry.all
                    ordered.forEach { spec ->
                        ProviderChip(spec.shortName, selected = choice == spec.id) { onChoice(spec.id) }
                    }
                }
                val picked = ProviderRegistry.byId(choice)
                if (picked != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        picked.keyHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(
                    visible = choice == ProviderRegistry.CUSTOM_ID,
                    enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                    exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec())
                ) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = draftBaseUrl,
                            onValueChange = onDraftBaseUrl,
                            label = { Text("Base URL") },
                            placeholder = { Text("https://example.com/v1") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Done
                            ),
                            supportingText = { Text("Forge calls {base}/chat/completions and {base}/models over HTTPS.") },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val saving = busy == AiBusy.SAVING || busy == AiBusy.CHECKING
                    Button(
                        onClick = onSave,
                        enabled = busy == null && draftKey.isNotBlank() && detection !is KeyDetection.Rejected,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight)
                    ) {
                        if (saving) {
                            LoadingIndicator(
                                Modifier.size(22.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            when (busy) {
                                AiBusy.CHECKING -> "Checking"
                                AiBusy.SAVING -> "Saving"
                                else -> "Save key"
                            }
                        )
                    }
                    if (state.hasKey) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onCancelEditing, enabled = busy == null, shapes = ButtonDefaults.shapes()) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }

        // AnimatedContent keeps the outgoing message on screen while it fades, and animates the
        // card's height between messages instead of jumping.
        AnimatedContent(
            targetState = state.status,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()))
                    .using(SizeTransform(clip = false))
            },
            label = "status"
        ) { status ->
            if (status != null) {
                StatusBanner(status, onDismiss = onDismissStatus, modifier = Modifier.padding(top = 14.dp))
            } else {
                Spacer(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ProviderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
        shape = CircleShape
    )
}

/** What Forge makes of the pasted key, before anything is sent anywhere. */
private data class DetectionUi(val text: String, val tone: Tone, val note: String?) {
    enum class Tone { NEUTRAL, GOOD, MAYBE, BAD }
}

private fun detectionUi(detection: KeyDetection, choice: String): DetectionUi? {
    if (choice != AiKeys.AUTO) {
        val spec = ProviderRegistry.byId(choice) ?: return null
        if (detection is KeyDetection.Rejected && detection.provider?.id == spec.id) {
            return DetectionUi("Can't use this key", DetectionUi.Tone.BAD, detection.reason)
        }
        return DetectionUi("Using ${spec.shortName} (picked)", DetectionUi.Tone.GOOD, null)
    }
    return when (detection) {
        KeyDetection.Empty -> DetectionUi(
            "Recognises Anthropic, OpenAI, Gemini, OpenRouter, Groq and xAI keys by shape",
            DetectionUi.Tone.NEUTRAL,
            "Mistral, Together and DeepSeek keys are confirmed online. Command Code and anything else: " +
                "pick the provider below."
        )
        is KeyDetection.Match ->
            if (detection.confident) DetectionUi("Detected: ${detection.provider.name}", DetectionUi.Tone.GOOD, detection.note)
            else DetectionUi("Looks like: ${detection.provider.name}", DetectionUi.Tone.MAYBE, detection.note
                ?: "Forge will confirm with ${detection.provider.shortName} before saving.")
        is KeyDetection.Ambiguous -> DetectionUi(
            "Could be ${detection.candidates.joinToString(" or ") { it.shortName }}",
            DetectionUi.Tone.MAYBE,
            "Forge will ask each one (no tokens spent) when you save, or pick the provider below."
        )
        // Command Code (and others) publish no key format, so an unknown shape is normal for
        // them: lead with what to do, not with a failure.
        is KeyDetection.Unknown -> DetectionUi(
            "Couldn't recognise this key - pick your provider below",
            DetectionUi.Tone.MAYBE,
            "Command Code keys can't be recognised by shape, so pick Command Code. If you leave Auto-detect " +
                "on, Forge tries ${detection.probeCandidates.joinToString(" and ") { it.shortName }} when you save."
        )
        is KeyDetection.Rejected -> DetectionUi("Can't use this key", DetectionUi.Tone.BAD, detection.reason)
    }
}

@Composable
private fun DetectionLine(detection: KeyDetection, choice: String) {
    val ui = remember(detection, choice) { detectionUi(detection, choice) } ?: return
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = ui,
        transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
        label = "detection"
    ) { d ->
        val colors = MaterialTheme.colorScheme
        val accent = when (d.tone) {
            DetectionUi.Tone.GOOD -> colors.primary
            DetectionUi.Tone.MAYBE -> colors.tertiary
            DetectionUi.Tone.BAD -> colors.error
            DetectionUi.Tone.NEUTRAL -> colors.onSurfaceVariant
        }
        Column {
            Pill(
                d.text,
                color = accent,
                filled = d.tone != DetectionUi.Tone.NEUTRAL,
                icon = when (d.tone) {
                    DetectionUi.Tone.GOOD -> Icons.Rounded.CheckCircle
                    DetectionUi.Tone.BAD -> Icons.Rounded.ErrorOutline
                    DetectionUi.Tone.MAYBE -> Icons.Rounded.Search
                    DetectionUi.Tone.NEUTRAL -> Icons.Rounded.AutoAwesome
                }
            )
            d.note?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (d.tone == DetectionUi.Tone.BAD) colors.error else colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusBanner(status: AiStatus, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val container = if (status.isError) colors.errorContainer else colors.secondaryContainer
    val content = if (status.isError) colors.onErrorContainer else colors.onSecondaryContainer
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            if (status.isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(status.text, style = MaterialTheme.typography.bodySmall, color = content, modifier = Modifier.weight(1f))
    }
}

// =====================================================================================
// Privacy
// =====================================================================================

@Composable
private fun PrivacyCard(provider: ProviderSpec, modifier: Modifier = Modifier) {
    val policy = provider.dataPolicy
    val colors = MaterialTheme.colorScheme
    ForgeCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                when (policy.risk) {
                    TrainingRisk.NOT_BY_DEFAULT -> "Not trained on by default"
                    TrainingRisk.FREE_TIER_TRAINS -> "Free tier trains on your data"
                    TrainingRisk.MAY_TRAIN -> "May be trained on"
                    TrainingRisk.DEPENDS_ON_ROUTE -> "Depends on the upstream provider"
                    TrainingRisk.ZDR_ENFORCED -> "Zero data retention on every request"
                    TrainingRisk.UNKNOWN -> "Not covered by Forge's research"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        policy.warning?.let { warning ->
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.errorContainer)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Rounded.WarningAmber, contentDescription = null,
                    tint = colors.onErrorContainer, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(warning, style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(policy.summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

// =====================================================================================
// Tier and cost
// =====================================================================================

/** Four labels must fit one connected row on a phone, so Ultra-cheap's button says "Ultra". */
private fun tierButtonLabel(tier: CostTier): String =
    if (tier.name == ModelRouter.ULTRA_CHEAP) "Ultra" else tier.label

@Composable
private fun TierCard(
    state: AiSettingsUiState,
    onTier: (CostTier) -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = state.selectedTier
    val tiers = state.tiers
    val preset = state.preset
    val provider = state.provider
    val colors = MaterialTheme.colorScheme
    ForgeCard(modifier = modifier, contentPadding = PaddingValues(20.dp)) {
        if (tiers.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
            ) {
                tiers.forEachIndexed { i, option ->
                    val tier = option.tier
                    ToggleButton(
                        checked = selected == tier,
                        onCheckedChange = { if (it) onTier(tier) },
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            tiers.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { role = Role.RadioButton }
                    ) {
                        Text(tierButtonLabel(tier), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
            // Each tier's monthly figure under its button, so the choice is made with the price in view.
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                tiers.forEach { option ->
                    Text(
                        option.monthly?.let { "≈$it/mo" } ?: "price ?",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (option.tier == selected) colors.primary else colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // The preset's own name and reasoning beat the generic tier blurb: they say which model
        // does which job and why.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                preset?.title ?: selected.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (preset != null && selected.name == ModelRouter.ULTRA_CHEAP) {
                Spacer(Modifier.width(8.dp))
                Pill("Recommended", color = colors.primary, filled = true, icon = Icons.Rounded.AutoAwesome)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(preset?.blurb ?: selected.blurb, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (provider?.dataPolicy?.risk == TrainingRisk.ZDR_ENFORCED) {
            Spacer(Modifier.height(10.dp))
            Pill("Zero data retention enforced", color = colors.secondary, filled = false, icon = Icons.Rounded.Shield)
        }
        provider?.billingNote?.let { note ->
            Spacer(Modifier.height(10.dp))
            Text(note, style = MaterialTheme.typography.bodySmall, color = colors.tertiary)
        }

        Spacer(Modifier.height(18.dp))
        val estimate = state.estimate
        Text(
            "Estimated monthly cost",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant
        )
        Text(
            when {
                estimate == null -> "-"
                estimate.totalUsd != null -> "≈ ${CostEstimator.formatUsd(estimate.totalUsd)} / month"
                else -> "Unknown"
            },
            style = MaterialTheme.typography.headlineMediumEmphasized,
            color = colors.onSurface
        )
        if (estimate != null && estimate.unpricedModels.isNotEmpty()) {
            Text(
                "No price on file for ${estimate.unpricedModels.joinToString()} (not in Forge's verified list), " +
                    "so no total is shown.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.tertiary
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            CostEstimator.ASSUMPTIONS,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant.copy(alpha = 0.8f)
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = colors.outlineVariant)
        Spacer(Modifier.height(8.dp))
        Text(
            "Which model does each job",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant
        )
        state.jobs.forEach { row -> JobRowView(row) }
    }
}

@Composable
private fun JobRowView(row: AiJobRow) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.task.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (row.overridden) {
                Pill("Override", color = colors.tertiary, filled = true, icon = Icons.Rounded.Tune)
                Spacer(Modifier.width(6.dp))
            }
        }
        if (row.error != null) {
            Text(row.error, style = MaterialTheme.typography.bodySmall, color = colors.error)
        } else {
            Text(
                row.model.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurface
            )
            row.price?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            row.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.tertiary)
            }
        }
    }
}

// =====================================================================================
// Advanced
// =====================================================================================

@Composable
private fun AdvancedHeader(open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "advancedChevron"
    )
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Advanced",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Pick the model per job, or change the API address",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = if (open) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { rotationZ = rotation }
        )
    }
}

private fun overrideLabel(taskKey: String): String =
    if (taskKey == ModelOverrides.DEFAULT_KEY) "Every job (default)"
    else runCatching { Task.valueOf(taskKey).label }.getOrDefault(taskKey)

@Composable
private fun OverrideRow(
    taskKey: String,
    state: AiSettingsUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val override = state.overrides[taskKey]
    val routed = state.jobs.firstOrNull { it.task.name == taskKey }
    val colors = MaterialTheme.colorScheme
    ForgeCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(overrideLabel(taskKey), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        override != null -> override
                        taskKey == ModelOverrides.DEFAULT_KEY -> "Not set: each job uses its tier model"
                        routed?.model != null -> "Tier default · ${routed.model}"
                        else -> routed?.error ?: "Tier default"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (override != null || routed?.model != null) FontFamily.Monospace else null,
                    color = if (override != null) colors.tertiary else colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Rounded.Tune, contentDescription = "Choose model", tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun BaseUrlCard(
    provider: ProviderSpec,
    current: String,
    busy: Boolean,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by remember(current) { mutableStateOf(current) }
    ForgeCard(modifier = modifier) {
        Text("API address", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            if (provider.isCustom) "Your endpoint's OpenAI-compatible base URL."
            else "Leave blank to use ${provider.baseUrl}. Use this for a regional or proxy endpoint that speaks the same API.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text(provider.baseUrl.ifBlank { "https://example.com/v1" }) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onSave(draft) }, enabled = !busy && draft.trim() != current) {
                Text("Save address")
            }
            if (current.isNotBlank() && !provider.isCustom) {
                TextButton(onClick = { onSave("") }, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                    Text("Use default")
                }
            }
        }
    }
}

// =====================================================================================
// Model picker
// =====================================================================================

@Composable
private fun ModelPickerSheet(
    taskKey: String,
    provider: ProviderSpec,
    state: AiSettingsUiState,
    onRetry: () -> Unit,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val current = state.overrides[taskKey]
    val routed = state.jobs.firstOrNull { it.task.name == taskKey }?.model
    val today = remember { LocalDate.now() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Model for ${overrideLabel(taskKey).lowercase()}", style = MaterialTheme.typography.titleLargeEmphasized)
            Text(
                "From ${provider.shortName}'s live model list. Models outside Forge's verified list have no " +
                    "price on file, and photo jobs need a model that can read images.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text("Search or type a model id") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
        }

        val models = state.models
        val loadedIds = (models as? AiModelsState.Loaded)?.takeIf { it.providerId == provider.id }?.ids
        val q = query.trim()
        val filtered = remember(loadedIds, q) {
            loadedIds.orEmpty().filter { q.isEmpty() || it.contains(q, ignoreCase = true) }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp)
        ) {
            item(key = "default", contentType = "model") {
                ModelOption(
                    title = if (taskKey == ModelOverrides.DEFAULT_KEY) "No default" else "Tier default",
                    subtitle = if (taskKey == ModelOverrides.DEFAULT_KEY) "Each job uses its tier model"
                    else routed?.let { "Currently $it" } ?: "Follows the cost tier",
                    selected = current == null,
                    onClick = { onPick(null) },
                    modifier = Modifier.animateItem()
                )
            }
            if (q.isNotEmpty() && loadedIds?.any { it == q } != true) {
                item(key = "typed", contentType = "model") {
                    ModelOption(
                        title = "Use \"$q\"",
                        subtitle = "Typed model id, sent as-is",
                        selected = current == q,
                        onClick = { onPick(q) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
            when (models) {
                AiModelsState.Idle, AiModelsState.Loading -> item(key = "loading", contentType = "state") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        LoadingIndicator(Modifier.size(48.dp))
                    }
                }
                is AiModelsState.Error -> item(key = "error", contentType = "state") {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            models.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(10.dp))
                        FilledTonalButton(onClick = onRetry) { Text("Retry") }
                    }
                }
                is AiModelsState.Loaded -> {
                    if (filtered.isEmpty()) {
                        item(key = "empty", contentType = "state") {
                            Text(
                                if (loadedIds.isNullOrEmpty()) "${provider.shortName} returned no models for this key."
                                else "No model matches \"$q\". You can still use the id as typed.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(24.dp)
                            )
                        }
                    } else {
                        items(filtered, key = { "m-$it" }, contentType = { "model" }) { id ->
                            val spec = provider.model(id)
                            // On Command Code the wire matters: it decides which endpoint Forge calls.
                            val wire = if (provider.kind == ProviderKind.COMMAND_CODE) {
                                if (CommandCodeProvider.usesMessages(id, spec)) "Messages API" else "Chat Completions"
                            } else null
                            val known = spec?.let {
                                (if (it.vision) "Reads images" else "Text only") + " · " +
                                    CostEstimator.priceLabel(it, today)
                            }
                            ModelOption(
                                title = id,
                                subtitle = listOfNotNull(wire, known).joinToString(" · ").ifEmpty { null },
                                selected = current == id,
                                monospace = true,
                                onClick = { onPick(id) },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelOption(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    monospace: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
                )
            }
        }
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = colors.onSecondaryContainer)
        }
    }
}
