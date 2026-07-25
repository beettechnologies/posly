package com.beettechnologies.posly.auth

import org.koin.core.module.Module
import org.koin.dsl.module

// No iOS app currently consumes sharedLogic; this satisfies the expect/actual
// contract so the iosArm64/iosSimulatorArm64 targets keep compiling.
actual val defaultBaseUrl: String = ""

actual fun platformAuthModule(): Module = module { }
