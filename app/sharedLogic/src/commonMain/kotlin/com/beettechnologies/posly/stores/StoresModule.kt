package com.beettechnologies.posly.stores

import com.beettechnologies.posly.auth.defaultBaseUrl
import org.koin.core.module.Module
import org.koin.dsl.module

val storesModule: Module = module {
    single<StoreApi> { KtorStoreApi(get(), defaultBaseUrl) }
    single<TaxProfileApi> { KtorTaxProfileApi(get(), defaultBaseUrl) }
}
