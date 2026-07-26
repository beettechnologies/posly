package com.beettechnologies.posly.users

import com.beettechnologies.posly.auth.defaultBaseUrl
import org.koin.core.module.Module
import org.koin.dsl.module

val usersModule: Module = module {
    single<UserApi> { KtorUserApi(get(), defaultBaseUrl) }
}
