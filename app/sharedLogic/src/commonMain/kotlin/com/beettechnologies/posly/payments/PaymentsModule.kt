package com.beettechnologies.posly.payments

import com.beettechnologies.posly.auth.defaultBaseUrl
import org.koin.core.module.Module
import org.koin.dsl.module

val paymentsModule: Module = module {
    single<PaymentApi> { KtorPaymentApi(get(), defaultBaseUrl) }
}
