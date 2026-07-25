package com.beettechnologies.posly.auth

import com.beettechnologies.posly.admin.StoreFormViewModel
import com.beettechnologies.posly.admin.StoreListViewModel
import com.beettechnologies.posly.admin.TaxProfileFormViewModel
import com.beettechnologies.posly.admin.TaxProfileListViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val sharedUiModule: Module = module {
    viewModel { LoginViewModel(get()) }
    viewModel { MfaViewModel(get()) }
    viewModel { StoreListViewModel(get()) }
    viewModel { StoreFormViewModel(get(), get()) }
    viewModel { TaxProfileListViewModel(get()) }
    viewModel { TaxProfileFormViewModel(get()) }
}
