package com.laksh.forge.feature.log

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
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
import kotlin.math.roundToInt

private val SLOTS = listOf(
    "BREAKFAST" to "Breakfast",
    "LUNCH" to "Lunch",
    "DINNER" to "Dinner",
    "SNACK" to "Snack"
)

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
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            if (captureForLabel) vm.analyseLabel(uri) else vm.analysePhoto(uri)
        }
    }

    fun launchCamera(forLabel: Boolean) {
        captureForLabel = forLabel
        val file = ImageUtils.newCaptureFile(context)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        captureUri = uri
        takePicture.launch(uri)
    }

    fun launchPicker(forLabel: Boolean) {
        captureForLabel = forLabel
        pickImage.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
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
            ConnectedChoiceGroup(
                options = LogMode.entries,
                selected = state.mode,
                onSelect = vm::setMode,
                label = { it.label }
            )
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
                                onRetry = vm::dismissError,
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
                            LogMode.MANUAL -> ManualMode(
                                text = state.manualText,
                                onText = vm::setManualText,
                                onEstimate = vm::analyseText
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
private fun ManualMode(text: String, onText: (String) -> Unit, onEstimate: () -> Unit) {
    ForgeCard {
        Text("Describe what you ate", style = MaterialTheme.typography.titleLargeEmphasized)
        Spacer(Modifier.height(4.dp))
        Text(
            "Be as specific as you can about portions and cooking method.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
            enabled = text.isNotBlank(),
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.align(Alignment.End)
        ) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Estimate")
        }
    }
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
    onRetry: () -> Unit,
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showLabelFallback) {
                Pill("Photograph label", icon = Icons.Outlined.PhotoCamera, filled = true, color = tint, onClick = onScanLabel)
                Pill("From gallery", icon = Icons.Outlined.PhotoLibrary, color = tint, onClick = onPickLabel)
            } else {
                Pill("Try again", icon = Icons.Outlined.Refresh, filled = true, color = tint, onClick = onRetry)
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
