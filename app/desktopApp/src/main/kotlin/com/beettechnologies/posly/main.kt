package com.beettechnologies.posly

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.beettechnologies.posly.auth.authModule
import com.beettechnologies.posly.auth.platformAuthModule
import com.beettechnologies.posly.auth.sharedUiModule
import com.beettechnologies.posly.cart.cartModule
import com.beettechnologies.posly.catalog.importModule
import com.beettechnologies.posly.devices.devicesModule
import com.beettechnologies.posly.finance.financeReportModule
import com.beettechnologies.posly.orders.ordersModule
import com.beettechnologies.posly.payments.paymentsModule
import com.beettechnologies.posly.products.productsModule
import com.beettechnologies.posly.receipts.receiptsModule
import com.beettechnologies.posly.reporting.reportingModule
import com.beettechnologies.posly.shifts.shiftsModule
import com.beettechnologies.posly.stores.storesModule
import com.beettechnologies.posly.users.usersModule
import org.koin.core.context.startKoin

fun main() {
    startKoin {
        modules(
            authModule,
            platformAuthModule(),
            sharedUiModule,
            storesModule,
            devicesModule,
            productsModule,
            cartModule,
            ordersModule,
            paymentsModule,
            receiptsModule,
            shiftsModule,
            usersModule,
            importModule,
            reportingModule,
            financeReportModule
        )
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Posly",
        ) {
            App()
        }
    }
}