package com.beettechnologies.posly.build

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Stub implementation of com.android.application for environments where
 * Google Maven is not reachable (e.g. sandboxed CI steps that only build
 * the server module). The real plugin is applied when the Android subprojects
 * are in scope (ANDROID_HOME set + Google Maven reachable).
 */
class AndroidApplicationStubPlugin : Plugin<Project> {
    override fun apply(project: Project) = Unit
}
