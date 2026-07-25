package com.beettechnologies.posly.devices

import com.beettechnologies.posly.auth.defaultBaseUrl
import org.koin.core.module.Module
import org.koin.dsl.module

val devicesModule: Module = module {
    single<DeviceApi> { KtorDeviceApi(get(), defaultBaseUrl) }
    single<DeviceCredentialsStore> { SettingsDeviceCredentialsStore(get()) }
}
