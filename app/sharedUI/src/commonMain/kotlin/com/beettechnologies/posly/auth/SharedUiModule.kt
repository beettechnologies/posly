package com.beettechnologies.posly.auth

import com.beettechnologies.posly.admin.FinanceReportsViewModel
import com.beettechnologies.posly.admin.ImportWizardViewModel
import com.beettechnologies.posly.admin.SsoConfigViewModel
import com.beettechnologies.posly.admin.StoreFormViewModel
import com.beettechnologies.posly.admin.StoreListViewModel
import com.beettechnologies.posly.admin.TaxProfileFormViewModel
import com.beettechnologies.posly.admin.TaxProfileListViewModel
import com.beettechnologies.posly.admin.UserFormViewModel
import com.beettechnologies.posly.admin.UserListViewModel
import com.beettechnologies.posly.devices.DeviceListViewModel
import com.beettechnologies.posly.devices.DevicePairingAdminViewModel
import com.beettechnologies.posly.devices.PairingViewModel
import com.beettechnologies.posly.pos.ManagerDashboardViewModel
import com.beettechnologies.posly.pos.PaymentViewModel
import com.beettechnologies.posly.pos.ProductDetailViewModel
import com.beettechnologies.posly.pos.ReceiptViewModel
import com.beettechnologies.posly.pos.RefundViewModel
import com.beettechnologies.posly.pos.SaleViewModel
import com.beettechnologies.posly.pos.ShiftViewModel
import com.beettechnologies.posly.pos.TransactionListViewModel
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
    viewModel { ProductDetailViewModel(get(), get()) }
    viewModel { PaymentViewModel(get(), get()) }
    viewModel { ReceiptViewModel(get()) }
    viewModel { RefundViewModel(get()) }
    viewModel { ShiftViewModel(get(), get()) }
    viewModel { UserListViewModel(get()) }
    viewModel { UserFormViewModel(get(), get()) }
    viewModel { SsoConfigViewModel(get()) }
    viewModel { AcceptInviteViewModel(get()) }
    viewModel { ImportWizardViewModel(get()) }
    viewModel { ManagerDashboardViewModel(get(), get()) }
    viewModel { TransactionListViewModel(get()) }
    viewModel { FinanceReportsViewModel(get(), get()) }
}
