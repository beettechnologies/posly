package com.beettechnologies.posly.flags

import com.beettechnologies.posly.auth.defaultBaseUrl
import org.koin.core.module.Module
import org.koin.dsl.module

val featureFlagsModule: Module = module {
    single<FeatureFlagApi> { KtorFeatureFlagApi(get(), defaultBaseUrl) }
}
