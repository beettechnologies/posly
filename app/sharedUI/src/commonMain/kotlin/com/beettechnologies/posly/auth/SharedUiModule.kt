package com.beettechnologies.posly.auth

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val sharedUiModule: Module = module {
    viewModel { LoginViewModel(get()) }
    viewModel { MfaViewModel(get()) }
}
