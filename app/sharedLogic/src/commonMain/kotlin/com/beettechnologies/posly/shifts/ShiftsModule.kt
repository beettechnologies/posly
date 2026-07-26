package com.beettechnologies.posly.shifts

import com.beettechnologies.posly.auth.defaultBaseUrl
import org.koin.core.module.Module
import org.koin.dsl.module

val shiftsModule: Module = module {
    single<ShiftApi> { KtorShiftApi(get(), defaultBaseUrl) }
}
