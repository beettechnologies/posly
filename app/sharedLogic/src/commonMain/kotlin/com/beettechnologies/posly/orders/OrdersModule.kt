package com.beettechnologies.posly.orders

import com.beettechnologies.posly.auth.defaultBaseUrl
import org.koin.core.module.Module
import org.koin.dsl.module

val ordersModule: Module = module {
    single<OrderApi> { KtorOrderApi(get(), defaultBaseUrl) }
}
