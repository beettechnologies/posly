package com.beettechnologies.posly.catalog

import com.beettechnologies.posly.auth.defaultBaseUrl
import org.koin.core.module.Module
import org.koin.dsl.module

val importModule: Module = module {
    single<ImportApi> { KtorImportApi(get(), defaultBaseUrl) }
}
