package com.laksh.forge.feature.wearables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

// CONTRACT STUB - the owning feature replaces this file. Keep the signatures below.

@Composable
fun ReadinessCard(modifier: Modifier = Modifier, onOpen: () -> Unit = {}) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("ReadinessCard") }
}

@Composable
fun WearableTrendsSection(modifier: Modifier = Modifier) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("WearableTrendsSection") }
}
