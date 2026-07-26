package com.beettechnologies.posly.catalog

import androidx.compose.runtime.Composable
import java.awt.FileDialog
import java.io.File

@Composable
actual fun rememberFilePickerLauncher(onPicked: (PickedFile) -> Unit): () -> Unit {
    return {
        // FileDialog.isVisible pumps its own native event loop while modal, so calling it
        // synchronously from a click handler does not freeze Compose's own render loop.
        val dialog = FileDialog(null as java.awt.Frame?, "Select a CSV file", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.endsWith(".csv", ignoreCase = true) }
        dialog.isVisible = true
        val fileName = dialog.file
        val directory = dialog.directory
        if (fileName != null && directory != null) {
            val file = File(directory, fileName)
            onPicked(PickedFile(fileName, file.readBytes()))
        }
    }
}
