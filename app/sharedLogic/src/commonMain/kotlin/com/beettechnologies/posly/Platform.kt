package com.beettechnologies.posly

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform