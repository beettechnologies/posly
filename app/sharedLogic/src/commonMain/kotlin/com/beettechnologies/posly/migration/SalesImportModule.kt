package com.beettechnologies.posly.migration

import com.beettechnologies.posly.auth.defaultBaseUrl
import org.koin.core.module.Module
import org.koin.dsl.module

val salesImportModule: Module = module {
    single<SalesImportApi> { KtorSalesImportApi(get(), defaultBaseUrl) }
}
