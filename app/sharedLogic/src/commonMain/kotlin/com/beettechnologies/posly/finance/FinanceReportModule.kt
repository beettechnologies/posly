package com.beettechnologies.posly.finance

import com.beettechnologies.posly.auth.defaultBaseUrl
import org.koin.core.module.Module
import org.koin.dsl.module

val financeReportModule: Module = module {
    single<FinanceReportApi> { KtorFinanceReportApi(get(), defaultBaseUrl) }
}
