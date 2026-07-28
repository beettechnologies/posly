package com.beettechnologies.posly.accessibility

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * Marks a composable (typically a dynamically-appearing error/info/status [androidx.compose.material3.Text])
 * as a WCAG 2.1 AA 4.1.3 "Status Message" - so assistive tech announces it the moment it appears,
 * without the user having to navigate to find it. Use [LiveRegionMode.Polite] (the only mode this
 * wraps): the announcement waits for the current speech to finish rather than interrupting it,
 * appropriate for validation/info/status text - never for anything so urgent it must interrupt
 * (this codebase has no such case; if one arises, use `Modifier.semantics { liveRegion = LiveRegionMode.Assertive }` directly instead of this helper).
 */
fun Modifier.statusMessage(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }
