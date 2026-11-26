package com.laksh.forge.feature.log

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.ui.components.AnimatedCounter
import com.laksh.forge.ui.components.ConnectedChoiceGroup
import com.laksh.forge.ui.components.ExpressiveLoading
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.ForgeSubScreen
import com.laksh.forge.ui.components.IconBadge
import com.laksh.forge.ui.components.MacroBar
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.ui.components.bounceClick
import com.laksh.forge.ui.theme.Macro
import com.laksh.forge.ui.theme.accentFor
import com.laksh.forge.ui.theme.tintedContainer
import com.laksh.forge.util.ImageUtils
import kotlin.math.abs
import kotlin.math.roundToInt

private val SLOTS = listOf(
    "BREAKFAST" to "Breakfast",
    "LUNCH" to "Lunch",
    "DINNER" to "Dinner",
    "SNACK" to "Snack"
)

/**
 * Five modes don't fit a phone width without ellipsizing ("Quick add", "Describe"), so below
 * this width the group scrolls sideways instead. The partly visible last tab hints at the scroll.
 */
private val MODE_ROW_MIN_WIDTH = 440.dp

/** What the body of the screen is showing; AnimatedContent morphs between these. */
private enum class Stage { INPUT, WORKING, ESTIMATE, PRODUCT }

private fun stageOf(s: LogState): Stage = when {
    s.phase == Phase.WORKING -> Stage.WORKING
    s.phase == Phase.RESULT && s.estimate != null -> Stage.ESTIMATE
    s.phase == Phase.RESULT && s.product != null -> Stage.PRODUCT
    else -> Stage.INPUT
}

@Composable
fun LogScreen(
    onDone: () -> Unit,
    vm: LogViewModel = viewModel(factory = LogViewModel.Factory)
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    // Camera capture writes into app cache and is exposed through FileProvider. Saved across
    // process death: the camera app can outlive us.
    var captureUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var captureForLabel by rememberSaveable { mutableStateOf(false) }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = captureUri
        if (success && uri != null) {
            if (captureForLabel) vm.analyseLabel(uri) else vm.analysePhoto(uri)
        } else {
            // Cancelled: the empty or partial capture file goes now, not at the next launch.
            ImageUtils.discardCapture(context, uri)
        }
        captureUri = null
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            if (captureForLabel) vm.analyseLabel(uri) else vm.analysePhoto(uri)
        }
    }

    fun launchPicker(forLabel: Boolean) {
        captureForLabel = forLabel
        pickImage.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    fun startCapture() {
        val file = ImageUtils.newCaptureFile(context)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        captureUri = uri
        try {
            takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            ImageUtils.discardCapture(context, uri)
            captureUri = null
            launchPicker(captureForLabel)
        } catch (e: SecurityException) {
            ImageUtils.discardCapture(context, uri)
            captureUri = null
            launchPicker(captureForLabel)
        }
    }

    // The manifest declares CAMERA (for the in-app pose camera), and Android then refuses
    // ACTION_IMAGE_CAPTURE with a SecurityException until the permission is granted.
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCapture() else launchPicker(captureForLabel)
    }

    fun launchCamera(forLabel: Boolean) {
        captureForLabel = forLabel
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startCapture() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(state.phase) {
        if (state.phase == Phase.SAVED) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onDone()
            vm.reset()
        }
    }

    val stage = stageOf(state)
    val motion = MaterialTheme.motionScheme
    val enterScale = motion.defaultSpatialSpec<Float>()
    val effects = motion.defaultEffectsSpec<Float>()
    val fastEffects = motion.fastEffectsSpec<Float>()

    ForgeSubScreen(
        title = "Log food",
        subtitle = SLOTS.firstOrNull { it.first == state.mealSlot }?.second,
        onBack = onDone
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 32.dp)
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val rowWidth = maxOf(maxWidth, MODE_ROW_MIN_WIDTH)
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    ConnectedChoiceGroup(
                        options = LogMode.entries,
                        selected = state.mode,
                        onSelect = vm::setMode,
                        label = { it.label },
                        modifier = Modifier.width(rowWidth)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            MealSlotRow(state.mealSlot, vm::setMealSlot)
            Spacer(Modifier.height(16.dp))

            AnimatedContent(
                targetState = stage,
                transitionSpec = {
                    (fadeIn(effects) + scaleIn(enterScale, initialScale = 0.94f))
                        .togetherWith(fadeOut(fastEffects) + scaleOut(fastEffects, targetScale = 0.98f))
                },
                label = "logStage"
            ) { target ->
                when (target) {
                    Stage.WORKING -> WorkingCard(state.statusText)
                    Stage.ESTIMATE -> EstimateCard(state = state, vm = vm)
                    Stage.PRODUCT -> ProductCard(state = state, vm = vm)
                    Stage.INPUT -> Column {
                        if (state.phase == Phase.ERROR) {
                            ErrorCard(
                                message = state.error.orEmpty(),
                                showLabelFallback = state.missingBarcode != null,
                                showQuickAdd = !state.hasApiKey,
                                onRetry = vm::dismissError,
                                onQuickAdd = { vm.setMode(LogMode.QUICK) },
                                onScanLabel = { launchCamera(forLabel = true) },
                                onPickLabel = { launchPicker(forLabel = true) }
                            )
                            Spacer(Modifier.height(14.dp))
                        }
                        when (state.mode) {
                            LogMode.PHOTO -> PhotoMode(
                                hint = state.hint,
                                onHint = vm::setHint,
                                storePhotos = state.storePhotos,
                                onCamera = { launchCamera(false) },
                                onGallery = { launchPicker(false) }
                            )
                            LogMode.BARCODE -> BarcodeMode(onBarcode = vm::onBarcode)
                            LogMode.LABEL -> LabelMode(
                                onCamera = { launchCamera(true) },
                                onGallery = { launchPicker(true) }
                            )
                            LogMode.QUICK -> QuickAddMode(
                                form = state.quick,
                                mealLabel = SLOTS.firstOrNull { it.first == state.mealSlot }?.second?.lowercase() ?: "this meal",
                                saving = state.saving,
                                error = state.error,
                                vm = vm
                            )
                            LogMode.MANUAL -> ManualMode(
                                text = state.manualText,
                                onText = vm::setManualText,
                                onEstimate = vm::analyseText,
                                hasAi = state.hasApiKey,
                                onQuickAdd = { vm.setMode(LogMode.QUICK) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MealSlotRow(current: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SLOTS.forEach { (key, label) ->
            Pill(
                text = label,
                filled = key == current,
                color = if (key == current) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onSelect(key) }
            )
        }
    }
}

@Composable
private fun PhotoMode(
    hint: String,
    onHint: (String) -> Unit,
    storePhotos: Boolean,
    onCamera: () -> Unit,
    onGallery: () -> Unit
) {
    ForgeCard {
        Text("Photograph your meal", style = MaterialTheme.typography.titleLargeEmphasized)
        Spacer(Modifier.height(4.dp))
        Text(
            "Get the whole plate in frame. Estimates lean deliberately high, so you're more likely to over-count than under-count.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigAction(Icons.Outlined.PhotoCamera, "Camera", Modifier.weight(1f), onCamera)
            BigAction(Icons.Outlined.PhotoLibrary, "Gallery", Modifier.weight(1f), onGallery)
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = hint,
            onValueChange = onHint,
            label = { Text("Add detail (optional)") },
            placeholder = { Text("e.g. cooked in ghee, large bowl") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            maxLines = 3,
            shape = RoundedCornerShape(16.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (storePhotos)
                "Photos are kept on this phone only, and sent to your AI provider solely to produce the estimate."
            else
                "Photo storage is off - images are analysed and then discarded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LabelMode(onCamera: () -> Unit, onGallery: () -> Unit) {
    ForgeCard {
        Text("Read a nutrition label", style = MaterialTheme.typography.titleLargeEmphasized)
        Spacer(Modifier.height(4.dp))
        Text(
            "Photograph the printed nutrition panel straight on. The values are read directly, then you choose how much you had.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigAction(Icons.Outlined.Receipt, "Photograph", Modifier.weight(1f), onCamera)
            BigAction(Icons.Outlined.PhotoLibrary, "Gallery", Modifier.weight(1f), onGallery)
        }
    }
}

@Composable
private fun BarcodeMode(onBarcode: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.78f)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.Black)
    ) {
        BarcodeScannerView(onBarcode = onBarcode, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ManualMode(
    text: String,
    onText: (String) -> Unit,
    onEstimate: () -> Unit,
    hasAi: Boolean,
    onQuickAdd: () -> Unit
) {
    ForgeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Describe what you ate",
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.weight(1f)
            )
            // Said up front because this tab looks like plain text entry but sends the text to
            // the user's AI provider - people who already know the numbers want Quick add.
            Pill("Uses AI", icon = Icons.Outlined.AutoAwesome, color = MaterialTheme.colorScheme.tertiary)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Your AI provider estimates calories and macros from the description. Be as specific as you can about portions and cooking method.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!hasAi) {
            Spacer(Modifier.height(12.dp))
            NoAiNotice(onQuickAdd)
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            placeholder = { Text("2 rotis, a bowl of dal, half a cup of rice") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            shape = RoundedCornerShape(16.dp)
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onEstimate,
            enabled = text.isNotBlank() && hasAi,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.align(Alignment.End)
        ) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Estimate")
        }
    }
}

/** Shown on AI tabs when no provider is usable, so the tab isn't a dead end. */
@Composable
private fun NoAiNotice(onQuickAdd: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.tintedContainer(scheme.tertiary, 0.12f))
            .padding(14.dp)
    ) {
        Text(
            "No AI provider is set up, so this tab can't estimate yet. If you roughly know the numbers, Quick add logs them with no key needed.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurface
        )
        Spacer(Modifier.height(10.dp))
        Pill("Use Quick add", icon = Icons.Outlined.Bolt, filled = true, color = scheme.tertiary, onClick = onQuickAdd)
    }
}

/** Holds the last non-null value so an exit animation still has something to draw. */
private class LastValue<T : Any> { var value: T? = null }

@Composable
private fun <T : Any> rememberLastNonNull(current: T?): T? {
    // Plain field, not snapshot state: writing it during composition must not trigger another.
    val holder = remember { LastValue<T>() }
    if (current != null) holder.value = current
    return holder.value
}

/**
 * Typed numbers, no AI: for the meal nobody photographed ("chicken burrito I made, about
 * 800 kcal, 40 g protein"). Works offline and with no key. Only name and kcal are required;
 * the macro hint is advisory and never blocks the save.
 */
@Composable
private fun QuickAddMode(
    form: QuickAddForm,
    mealLabel: String,
    saving: Boolean,
    error: String?,
    vm: LogViewModel
) {
    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    // Explicit requesters rather than moveFocus(Next): the "More" toggle between the rows is
    // clickable and could otherwise steal the IME's next hop.
    val nameFocus = remember { FocusRequester() }
    val kcalFocus = remember { FocusRequester() }
    val proteinFocus = remember { FocusRequester() }
    val carbsFocus = remember { FocusRequester() }
    val fatFocus = remember { FocusRequester() }
    val fibreFocus = remember { FocusRequester() }

    val kcalValue = remember(form.kcal) { QuickAddMath.parse(form.kcal) }
    val mismatch = remember(form.kcal, form.protein, form.carbs, form.fat) {
        QuickAddMath.mismatch(form.kcal, form.protein, form.carbs, form.fat)
    }
    val shownMismatch = rememberLastNonNull(mismatch)
    val nameProblem = if (form.attempted) form.nameProblem else null
    val kcalProblem = if (form.attempted) form.kcalProblem else null

    fun submit() {
        if (saving) return
        if (vm.saveQuickAdd()) {
            // The success haptic fires on the SAVED phase, shared with every other mode.
            focusManager.clearFocus()
        } else {
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            when {
                form.nameProblem != null -> nameFocus.requestFocus()
                form.kcalProblem != null -> kcalFocus.requestFocus()
            }
        }
    }

    ForgeCard {
        InlineError(error, vm::clearError)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Add by the numbers",
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.weight(1f)
            )
            Pill("No AI", icon = Icons.Outlined.Bolt, color = scheme.primary)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "For food you didn't photograph. What you type is logged exactly as entered: no estimate, no safety margin, works offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = form.name,
            onValueChange = vm::setQuickName,
            label = { Text("What you ate") },
            placeholder = { Text("e.g. Homemade chicken burrito") },
            singleLine = true,
            isError = nameProblem != null,
            supportingText = if (nameProblem != null) {
                { Text(nameProblem.message) }
            } else null,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(onNext = { kcalFocus.requestFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(nameFocus),
            shape = RoundedCornerShape(16.dp)
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            QuickNumField(
                value = form.kcal,
                onValue = vm::setQuickKcal,
                label = "Calories",
                suffix = "kcal",
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
                onIme = { proteinFocus.requestFocus() },
                focusRequester = kcalFocus,
                problem = kcalProblem,
                modifier = Modifier.weight(1f)
            )
            QuickNumField(
                value = form.protein,
                onValue = vm::setQuickProtein,
                label = "Protein",
                suffix = "g",
                // With the extra fields hidden, protein is the last stop and Done saves.
                imeAction = if (form.moreOpen) ImeAction.Next else ImeAction.Done,
                onIme = { if (form.moreOpen) carbsFocus.requestFocus() else submit() },
                focusRequester = proteinFocus,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(6.dp))
        val chevron by animateFloatAsState(
            targetValue = if (form.moreOpen) 180f else 0f,
            animationSpec = motion.defaultSpatialSpec<Float>(),
            label = "quickMoreChevron"
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .bounceClick(onClick = vm::toggleQuickMore, pressedScale = 0.98f)
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (form.moreOpen) "Fewer details" else "More: carbs, fat, fibre",
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = scheme.primary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.graphicsLayer { rotationZ = chevron }
            )
        }

        AnimatedVisibility(
            visible = form.moreOpen,
            enter = fadeIn(motion.defaultEffectsSpec<Float>()) + expandVertically(motion.defaultSpatialSpec<IntSize>()),
            exit = fadeOut(motion.fastEffectsSpec<Float>()) + shrinkVertically(motion.fastSpatialSpec<IntSize>())
        ) {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickNumField(
                        value = form.carbs,
                        onValue = vm::setQuickCarbs,
                        label = "Carbs",
                        suffix = "g",
                        imeAction = ImeAction.Next,
                        onIme = { fatFocus.requestFocus() },
                        focusRequester = carbsFocus,
                        modifier = Modifier.weight(1f)
                    )
                    QuickNumField(
                        value = form.fat,
                        onValue = vm::setQuickFat,
                        label = "Fat",
                        suffix = "g",
                        imeAction = ImeAction.Next,
                        onIme = { fibreFocus.requestFocus() },
                        focusRequester = fatFocus,
                        modifier = Modifier.weight(1f)
                    )
                    QuickNumField(
                        value = form.fibre,
                        onValue = vm::setQuickFibre,
                        label = "Fibre",
                        suffix = "g",
                        imeAction = ImeAction.Done,
                        onIme = { submit() },
                        focusRequester = fibreFocus,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "All optional - anything left blank counts as 0.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }

        AnimatedVisibility(
            visible = mismatch != null,
            enter = fadeIn(motion.defaultEffectsSpec<Float>()) + expandVertically(motion.defaultSpatialSpec<IntSize>()),
            exit = fadeOut(motion.fastEffectsSpec<Float>()) + shrinkVertically(motion.fastSpatialSpec<IntSize>())
        ) {
            shownMismatch?.let { m -> MacroHint(m) }
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { submit() },
            enabled = !saving,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ButtonDefaults.MediumContainerHeight)
        ) {
            Icon(Icons.Rounded.Check, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(
                when {
                    saving -> "Saving..."
                    kcalValue != null && kcalValue > 0.0 -> "Log ${kcalValue.roundToInt()} kcal"
                    else -> "Log it"
                },
                style = MaterialTheme.typography.titleSmallEmphasized
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Logs to $mealLabel - change it with the meal chips above.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )
    }
}

/**
 * The 4/4/9 cross-check, worded as a nudge: the typed kcal is saved regardless, because a
 * label or recipe total is often more accurate than macros rounded to whole grams.
 */
@Composable
private fun MacroHint(m: MacroMismatch) {
    val scheme = MaterialTheme.colorScheme
    val pct = (abs(m.deviation) * 100).roundToInt()
    val direction = if (m.deviation > 0) "more" else "less"
    Row(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.tintedContainer(scheme.secondary, 0.12f))
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = scheme.secondary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Those macros add up to about ${m.impliedRounded} kcal, $pct% $direction than the " +
                "${m.typedKcal.roundToInt()} you entered. Worth a quick check, though rounding, " +
                "fibre or alcohol can explain a gap. It saves as you typed it.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurface
        )
    }
}

@Composable
private fun QuickNumField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    suffix: String,
    imeAction: ImeAction,
    onIme: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Decimal,
    problem: QuickAddProblem? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        isError = problem != null,
        supportingText = if (problem != null) {
            { Text(problem.message) }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onIme() }, onDone = { onIme() }),
        modifier = modifier.focusRequester(focusRequester),
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
private fun BigAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .bounceClick(onClick = onClick, pressedScale = 0.94f)
            .background(MaterialTheme.colorScheme.tintedContainer(MaterialTheme.colorScheme.primary, 0.10f))
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconBadge(icon, size = 48.dp)
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.labelLargeEmphasized)
    }
}

@Composable
private fun WorkingCard(status: String) {
    ForgeCard(contentPadding = PaddingValues(vertical = 36.dp, horizontal = 24.dp)) {
        ExpressiveLoading(
            modifier = Modifier.fillMaxWidth(),
            message = status.ifBlank { "Working..." },
            size = 64.dp
        )
    }
}

@Composable
private fun ErrorCard(
    message: String,
    showLabelFallback: Boolean,
    showQuickAdd: Boolean,
    onRetry: () -> Unit,
    onQuickAdd: () -> Unit,
    onScanLabel: () -> Unit,
    onPickLabel: () -> Unit
) {
    ForgeCard(color = MaterialTheme.colorScheme.errorContainer) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
        Spacer(Modifier.height(14.dp))
        val tint = MaterialTheme.colorScheme.onErrorContainer
        // Flow, not Row: with the label fallback and Quick add both offered, three pills
        // overflow a narrow phone.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showLabelFallback) {
                Pill("Photograph label", icon = Icons.Outlined.PhotoCamera, filled = true, color = tint, onClick = onScanLabel)
                Pill("From gallery", icon = Icons.Outlined.PhotoLibrary, color = tint, onClick = onPickLabel)
            } else {
                Pill("Try again", icon = Icons.Outlined.Refresh, filled = true, color = tint, onClick = onRetry)
            }
            // Every AI-mode failure without a key is a dead end; this is the way out of it.
            if (showQuickAdd) {
                Pill("Quick add", icon = Icons.Outlined.Bolt, color = tint, onClick = onQuickAdd)
            }
        }
    }
}

/** A save failure shown inside a review card, so the edits on it survive. */
@Composable
private fun InlineError(message: String?, onDismiss: () -> Unit) {
    if (message.isNullOrBlank()) return
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f)
        )
        Pill("OK", color = MaterialTheme.colorScheme.onErrorContainer, onClick = onDismiss)
    }
    Spacer(Modifier.height(12.dp))
}

/** Editable review of an AI estimate. Nothing is saved until the user confirms. */
@Composable
private fun EstimateCard(state: LogState, vm: LogViewModel) {
    val est = state.estimate ?: return
    var name by remember(est) { mutableStateOf(est.name) }
    var kcal by remember(est) { mutableStateOf(est.kcal.roundToInt().toString()) }
    var protein by remember(est) { mutableStateOf(est.proteinG.roundToInt().toString()) }
    var carbs by remember(est) { mutableStateOf(est.carbsG.roundToInt().toString()) }
    var fat by remember(est) { mutableStateOf(est.fatG.roundToInt().toString()) }

    ForgeCard {
        InlineError(state.error, vm::clearError)

        state.photo?.let { bmp ->
            // remember: asImageBitmap wraps the bitmap; doing it per recomposition is waste.
            val image = remember(bmp) { bmp.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(20.dp))
            )
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("What it was") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 2,
            shape = RoundedCornerShape(16.dp)
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConfidenceDot(est.confidence)
            Spacer(Modifier.width(6.dp))
            Text(
                "${est.confidence.lowercase().replaceFirstChar { it.uppercase() }} confidence · ${est.servingDesc}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (est.note.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                est.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (est.components.size > 1) {
            Spacer(Modifier.height(4.dp))
            SectionHeader("On the plate")
            est.components.forEach { c ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${c.name} · ${c.portion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${c.kcal.roundToInt()}",
                        style = MaterialTheme.typography.bodySmallEmphasized
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField(kcal, { kcal = it }, "kcal", Modifier.weight(1.2f))
            NumField(protein, { protein = it }, "P (g)", Modifier.weight(1f))
            NumField(carbs, { carbs = it }, "C (g)", Modifier.weight(1f))
            NumField(fat, { fat = it }, "F (g)", Modifier.weight(1f))
        }

        state.rawKcal?.let { raw ->
            val padded = kcal.toDoubleOrNull() ?: est.kcal
            if (state.safetyMarginPct > 0 && padded > raw) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Includes a ${state.safetyMarginPct}% safety margin (model said ${raw.roundToInt()} kcal).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        ReviewActions(
            saving = state.saving,
            onDiscard = vm::reset,
            onSave = {
                vm.saveEstimate(
                    editedName = name,
                    editedKcal = kcal.toDoubleOrNull() ?: est.kcal,
                    protein = protein.toDoubleOrNull() ?: est.proteinG,
                    carbs = carbs.toDoubleOrNull() ?: est.carbsG,
                    fat = fat.toDoubleOrNull() ?: est.fatG
                )
            }
        )
    }
}

/** Scanned product: the user sets a gram amount and everything scales from per-100g. */
@Composable
private fun ProductCard(state: LogState, vm: LogViewModel) {
    val p = state.product ?: return
    val grams = state.grams.replace(',', '.').toDoubleOrNull() ?: 0.0
    val f = grams / 100.0

    ForgeCard {
        InlineError(state.error, vm::clearError)

        Text(p.name, style = MaterialTheme.typography.titleLargeEmphasized)
        p.brand?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.grams,
            onValueChange = vm::setGrams,
            label = { Text("Amount (g)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        )
        p.servingSizeG?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                "Label serving: ${p.servingDesc}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            // Rolls as the gram amount is typed, so the effect of a portion change is felt.
            AnimatedCounter(
                value = (p.kcalPer100g * f).roundToInt(),
                style = MaterialTheme.typography.displaySmallEmphasized
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "kcal",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        Spacer(Modifier.height(14.dp))
        MacroBar("Protein", p.proteinPer100g * f, (p.proteinPer100g * f).roundToInt().coerceAtLeast(1), Macro.protein)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val scheme = MaterialTheme.colorScheme
            Text("C ${(p.carbsPer100g * f).roundToInt()}g", style = MaterialTheme.typography.labelLarge, color = scheme.accentFor(Macro.carbs))
            Text("F ${(p.fatPer100g * f).roundToInt()}g", style = MaterialTheme.typography.labelLarge, color = scheme.accentFor(Macro.fat))
            Text("Fibre ${(p.fiberPer100g * f).roundToInt()}g", style = MaterialTheme.typography.labelLarge, color = scheme.accentFor(Macro.fiber))
        }

        Spacer(Modifier.height(18.dp))
        ReviewActions(
            saving = state.saving,
            saveEnabled = grams > 0.0,
            onDiscard = vm::reset,
            onSave = vm::saveProduct
        )
    }
}

@Composable
private fun ReviewActions(
    saving: Boolean,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean = true
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onDiscard,
            enabled = !saving,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = ButtonDefaults.MediumContainerHeight)
        ) { Text("Discard") }
        Button(
            onClick = onSave,
            enabled = saveEnabled && !saving,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier
                .weight(1.4f)
                .heightIn(min = ButtonDefaults.MediumContainerHeight)
        ) {
            Icon(Icons.Rounded.Check, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(if (saving) "Saving..." else "Save", style = MaterialTheme.typography.titleSmallEmphasized)
        }
    }
}

@Composable
private fun NumField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.filter { c -> c.isDigit() || c == '.' }) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
private fun ConfidenceDot(confidence: String) {
    val scheme = MaterialTheme.colorScheme
    val color = when (confidence.uppercase()) {
        "HIGH" -> scheme.accentFor(Macro.protein)
        "LOW" -> scheme.error
        else -> scheme.accentFor(Macro.carbs)
    }
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}
