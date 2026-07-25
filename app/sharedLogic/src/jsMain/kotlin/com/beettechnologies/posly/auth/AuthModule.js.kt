package com.beettechnologies.posly.auth

import org.koin.core.module.Module
import org.koin.dsl.module

// No web app currently consumes sharedLogic; this satisfies the expect/actual
// contract so the js target keeps compiling.
actual val defaultBaseUrl: String = ""

actual fun platformAuthModule(): Module = module { }
