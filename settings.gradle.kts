rootProject.name = "Posly"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":server")

// Android/KMP modules require the Android SDK. Skip them when ANDROID_HOME is not set
// so that server-only tasks (e.g. :server:test) work without the full Android toolchain.
if (System.getenv("ANDROID_HOME") != null) {
    include(":app:androidApp")
    include(":app:desktopApp")
    include(":app:sharedLogic")
    include(":app:sharedUI")
    include(":core")
}