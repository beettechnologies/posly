package com.beettechnologies.posly.auth

import com.beettechnologies.posly.admin.StoreFormViewModel
import com.beettechnologies.posly.admin.StoreListViewModel
import com.beettechnologies.posly.admin.TaxProfileFormViewModel
import com.beettechnologies.posly.admin.TaxProfileListViewModel
import com.beettechnologies.posly.devices.DeviceListViewModel
import com.beettechnologies.posly.devices.DevicePairingAdminViewModel
import com.beettechnologies.posly.devices.PairingViewModel
import com.beettechnologies.posly.pos.SaleViewModel
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
    viewModel { PairingViewModel(get(), get()) }
    viewModel { DevicePairingAdminViewModel(get(), get()) }
    viewModel { DeviceListViewModel(get(), get()) }
    viewModel { SaleViewModel(get(), get(), get(), get()) }
}
