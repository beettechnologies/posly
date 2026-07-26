package com.beettechnologies.posly.reporting

import com.beettechnologies.posly.auth.defaultBaseUrl
import org.koin.core.module.Module
import org.koin.dsl.module

val reportingModule: Module = module {
    single<ReportingApi> { KtorReportingApi(get(), defaultBaseUrl) }
}
