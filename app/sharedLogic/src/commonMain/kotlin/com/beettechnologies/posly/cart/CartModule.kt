package com.beettechnologies.posly.cart

import com.beettechnologies.posly.auth.defaultBaseUrl
import org.koin.core.module.Module
import org.koin.dsl.module

val cartModule: Module = module {
    single<CartApi> { KtorCartApi(get(), defaultBaseUrl) }
    single<CartSessionStore> { SettingsCartSessionStore(get()) }
}
