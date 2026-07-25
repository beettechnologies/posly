package com.beettechnologies.posly.devices

import androidx.compose.ui.graphics.ImageBitmap

/** Renders [content] as a square QR code bitmap, or null if generation isn't supported on this platform. */
expect fun generateQrCodeImageBitmap(content: String, size: Int = 512): ImageBitmap?
