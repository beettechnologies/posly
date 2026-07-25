package com.beettechnologies.posly.products

import com.beettechnologies.posly.auth.defaultBaseUrl
import org.koin.core.module.Module
import org.koin.dsl.module

val productsModule: Module = module {
    single<ProductSearchApi> { KtorProductSearchApi(get(), defaultBaseUrl) }
}
