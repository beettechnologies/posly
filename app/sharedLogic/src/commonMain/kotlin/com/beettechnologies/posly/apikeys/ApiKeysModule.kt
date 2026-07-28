package com.beettechnologies.posly.apikeys

import com.beettechnologies.posly.auth.defaultBaseUrl
import org.koin.core.module.Module
import org.koin.dsl.module

val apiKeysModule: Module = module {
    single<ApiKeyApi> { KtorApiKeyApi(get(), defaultBaseUrl) }
}
