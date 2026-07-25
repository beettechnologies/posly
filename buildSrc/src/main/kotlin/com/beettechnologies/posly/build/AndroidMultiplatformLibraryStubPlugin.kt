package com.beettechnologies.posly.build

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Stub implementation of com.android.kotlin.multiplatform.library for
 * environments where Google Maven is not reachable.
 */
class AndroidMultiplatformLibraryStubPlugin : Plugin<Project> {
    override fun apply(project: Project) = Unit
}
