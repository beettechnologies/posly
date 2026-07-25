package com.beettechnologies.posly.devices

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
actual fun QrScanner(onCodeScanned: (String) -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(240.dp).padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("Camera scanning isn't available on Desktop - enter the pairing code below instead.")
    }
}
