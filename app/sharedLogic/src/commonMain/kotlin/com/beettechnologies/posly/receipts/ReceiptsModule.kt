package com.beettechnologies.posly.receipts

import com.beettechnologies.posly.auth.defaultBaseUrl
import org.koin.core.module.Module
import org.koin.dsl.module

val receiptsModule: Module = module {
    single<ReceiptApi> { KtorReceiptApi(get(), defaultBaseUrl) }
}
