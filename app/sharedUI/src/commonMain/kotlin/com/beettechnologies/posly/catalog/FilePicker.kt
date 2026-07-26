package com.beettechnologies.posly.catalog

import androidx.compose.runtime.Composable

data class PickedFile(val name: String, val bytes: ByteArray)

/**
 * Returns a launcher function - call it to open the platform's native file picker. [onPicked] is
 * invoked with the chosen file's name and raw bytes; nothing is invoked if the user cancels.
 */
@Composable
expect fun rememberFilePickerLauncher(onPicked: (PickedFile) -> Unit): () -> Unit
