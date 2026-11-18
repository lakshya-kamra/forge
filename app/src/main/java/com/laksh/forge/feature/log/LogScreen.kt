package com.laksh.forge.feature.log

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.MacroBar
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.ui.theme.Macro
import com.laksh.forge.util.ImageUtils
import kotlin.math.roundToInt

private val SLOTS = listOf(
    "BREAKFAST" to "Breakfast",
    "LUNCH" to "Lunch",
    "DINNER" to "Dinner",
    "SNACK" to "Snack"
)

@Composable
fun LogScreen(
    onDone: () -> Unit,
    vm: LogViewModel = viewModel(factory = LogViewModel.Factory)
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Camera capture writes into app cache and is exposed through FileProvider.
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    var captureForLabel by remember { mutableStateOf(false) }

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
            onDone()
            vm.reset()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 40.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Log food",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(14.dp))

        ModeTabs(state.mode, vm::setMode)
        Spacer(Modifier.height(14.dp))

        MealSlotRow(state.mealSlot, vm::setMealSlot)
        Spacer(Modifier.height(16.dp))

        when {
            state.phase == Phase.WORKING -> WorkingCard(state.statusText)

            state.phase == Phase.RESULT && state.estimate != null ->
                EstimateCard(state = state, vm = vm)

            state.phase == Phase.RESULT && state.product != null ->
                ProductCard(state = state, vm = vm)

            else -> {
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

@Composable
private fun ModeTabs(mode: LogMode, onSelect: (LogMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(
            LogMode.PHOTO to "Photo",
            LogMode.BARCODE to "Barcode",
            LogMode.MANUAL to "Describe"
        ).forEach { (m, label) ->
            val selected = m == mode
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .clickable { onSelect(m) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
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
        Text(
            "Photograph your meal",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Get the whole plate in frame. Estimates lean deliberately high, so you're more likely to over-count than under-count.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigAction(
                icon = Icons.Outlined.PhotoCamera,
                label = "Camera",
                modifier = Modifier.weight(1f),
                onClick = onCamera
            )
            BigAction(
                icon = Icons.Outlined.PhotoLibrary,
                label = "Gallery",
                modifier = Modifier.weight(1f),
                onClick = onGallery
            )
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = hint,
            onValueChange = onHint,
            label = { Text("Add detail (optional)") },
            placeholder = { Text("e.g. cooked in ghee, large bowl") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            maxLines = 3
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (storePhotos)
                "Photos are kept on this phone only, and sent to Anthropic solely to produce the estimate."
            else
                "Photo storage is off - images are analysed and then discarded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun BarcodeMode(onBarcode: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.78f)
            .clip(RoundedCornerShape(22.dp))
            .background(Color.Black)
    ) {
        BarcodeScannerView(onBarcode = onBarcode, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ManualMode(text: String, onText: (String) -> Unit, onEstimate: () -> Unit) {
    ForgeCard {
        Text(
            "Describe what you ate",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Be as specific as you can about portions and cooking method.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            placeholder = { Text("2 rotis, a bowl of dal, half a cup of rice") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6
        )
        Spacer(Modifier.height(14.dp))
        Pill(
            text = "Estimate",
            icon = Icons.Outlined.AutoAwesome,
            filled = true,
            onClick = onEstimate,
            modifier = Modifier.align(Alignment.End)
        )
    }
}

@Composable
private fun BigAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun WorkingCard(status: String) {
    ForgeCard(contentPadding = PaddingValues(28.dp)) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(38.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                status.ifBlank { "Working..." },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
    ForgeCard(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showLabelFallback) {
                Pill("Photograph label", icon = Icons.Outlined.PhotoCamera, filled = true, onClick = onScanLabel)
                Pill("From gallery", icon = Icons.Outlined.PhotoLibrary, onClick = onPickLabel)
            } else {
                Pill("Try again", icon = Icons.Outlined.Refresh, filled = true, onClick = onRetry)
            }
        }
    }
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
        state.photo?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("What it was") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 2
        )

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConfidenceDot(est.confidence)
            Spacer(Modifier.width(6.dp))
            Text(
                "${est.confidence.lowercase().replaceFirstChar { it.uppercase() }} confidence · ${est.servingDesc}",
                style = MaterialTheme.typography.labelSmall,
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
            Spacer(Modifier.height(12.dp))
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
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Discard", onClick = vm::reset)
            Spacer(Modifier.weight(1f))
            Pill(
                "Save",
                icon = Icons.Outlined.Check,
                filled = true,
                onClick = {
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
}

/** Scanned product: the user sets a gram amount and everything scales from per-100g. */
@Composable
private fun ProductCard(state: LogState, vm: LogViewModel) {
    val p = state.product ?: return
    val grams = state.grams.replace(',', '.').toDoubleOrNull() ?: 0.0
    val f = grams / 100.0

    ForgeCard {
        Text(
            p.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
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
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Decimal
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                "${(p.kcalPer100g * f).roundToInt()}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "kcal",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(14.dp))
        MacroBar("Protein", p.proteinPer100g * f, (p.proteinPer100g * f).roundToInt().coerceAtLeast(1), Macro.protein)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("C ${(p.carbsPer100g * f).roundToInt()}g", style = MaterialTheme.typography.labelMedium, color = Macro.carbs)
            Text("F ${(p.fatPer100g * f).roundToInt()}g", style = MaterialTheme.typography.labelMedium, color = Macro.fat)
            Text("Fibre ${(p.fiberPer100g * f).roundToInt()}g", style = MaterialTheme.typography.labelMedium, color = Macro.fiber)
        }

        Spacer(Modifier.height(18.dp))
        Row {
            Pill("Discard", onClick = vm::reset)
            Spacer(Modifier.weight(1f))
            Pill("Save", icon = Icons.Outlined.Check, filled = true, onClick = vm::saveProduct)
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
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Decimal
        ),
        modifier = modifier
    )
}

@Composable
private fun ConfidenceDot(confidence: String) {
    val color = when (confidence.uppercase()) {
        "HIGH" -> Macro.protein
        "LOW" -> MaterialTheme.colorScheme.error
        else -> Macro.carbs
    }
    Box(
        Modifier
            .size(8.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(color)
    )
}
