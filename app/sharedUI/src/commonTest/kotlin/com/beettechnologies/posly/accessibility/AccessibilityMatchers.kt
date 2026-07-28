package com.beettechnologies.posly.accessibility

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher

/** Asserts a node's accessibility [Role] (e.g. Button, RadioButton) - what a screen reader announces the control as. */
fun hasRole(role: Role): SemanticsMatcher = SemanticsMatcher("has role = $role") { node ->
    node.config.getOrNull(SemanticsProperties.Role) == role
}

/** Asserts a node's click action carries the given accessible label - what a screen reader announces as the action ("double tap to <label>"). */
fun hasOnClickLabel(label: String): SemanticsMatcher = SemanticsMatcher("has onClick label = \"$label\"") { node ->
    node.config.getOrNull(SemanticsActions.OnClick)?.label == label
}

/** Asserts a node is marked as a live region (WCAG 2.1 AA 4.1.3 Status Messages) at the given [LiveRegionMode]. */
fun hasLiveRegion(mode: LiveRegionMode = LiveRegionMode.Polite): SemanticsMatcher = SemanticsMatcher("has liveRegion = $mode") { node ->
    node.config.getOrNull(SemanticsProperties.LiveRegion) == mode
}
