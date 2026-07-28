import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":app:sharedUI"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.core)
    implementation(libs.koin.android)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.beettechnologies.posly"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.beettechnologies.posly"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

/**
 * Enforces the release APK size budget documented in PERFORMANCE.md - the native equivalent of a
 * web app's Lighthouse/bundle-size CI gate, since this Compose Multiplatform app has no
 * browser-renderable target to run Lighthouse against (see PERFORMANCE.md for why).
 *
 * The default budget (40MB) is measured headroom above the actual unminified release APK
 * (~36MB as of this task's introduction - `isMinifyEnabled = false` above, see PERFORMANCE.md's
 * disclosed limitation on why R8 isn't turned on yet), not an arbitrary number. Override with
 * `-PapkSizeBudgetBytes=<bytes>` for a one-off check against a different threshold.
 */
// Captured at configuration time (not inside doLast) - reading project/providers state lazily at
// execution time breaks configuration-cache serialization (Task.project is execution-time-unsafe).
val apkSizeBudgetBytes: Long = providers.gradleProperty("apkSizeBudgetBytes")
    .map { it.toLong() }
    .getOrElse(40L * 1024 * 1024)
val releaseApkDirProvider = layout.buildDirectory.dir("outputs/apk/release")

tasks.register("checkApkSizeBudget") {
    group = "verification"
    description = "Fails if the assembled release APK exceeds the size budget (see PERFORMANCE.md)."
    dependsOn("assembleRelease")

    val apkDirFile = releaseApkDirProvider.get().asFile
    val budgetBytes = apkSizeBudgetBytes

    doLast {
        val apk = apkDirFile.listFiles { file -> file.extension == "apk" }?.firstOrNull()
            ?: error("No release APK found in $apkDirFile - did assembleRelease actually run?")

        val sizeBytes = apk.length()
        val sizeMb = sizeBytes / (1024.0 * 1024.0)
        val budgetMb = budgetBytes / (1024.0 * 1024.0)
        println("Release APK (%s): %.2f MB, budget %.2f MB".format(apk.name, sizeMb, budgetMb))

        if (sizeBytes > budgetBytes) {
            throw GradleException(
                "Release APK size %.2f MB exceeds the %.2f MB budget (see PERFORMANCE.md). ".format(sizeMb, budgetMb) +
                    "If this growth is expected and deliberate, raise the budget in this task explicitly rather than " +
                    "letting CI silently pass a regression; if not, check what dependency/asset was just added."
            )
        }
    }
}