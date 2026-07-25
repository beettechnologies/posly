package com.beettechnologies.posly.devices

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Live camera preview that decodes a QR code and reports its raw text via
 * [onCodeScanned]. Android only - other platforms render a fallback message
 * directing the user to the manual code entry field instead.
 */
@Composable
expect fun QrScanner(onCodeScanned: (String) -> Unit, modifier: Modifier = Modifier)
