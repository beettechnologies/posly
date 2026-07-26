package com.beettechnologies.posly.catalog

import androidx.compose.runtime.Composable

@Composable
actual fun rememberFilePickerLauncher(onPicked: (PickedFile) -> Unit): () -> Unit {
    // Not available yet - a real implementation needs UIDocumentPickerViewController.
    return {}
}
