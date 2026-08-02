plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-parcelize")
}

val companionVersionName = providers.gradleProperty("rpcs3x6VersionName")
    .orElse(providers.environmentVariable("RPCS3X6_VERSION_NAME"))
    .getOrElse("0.0.0.1")
val companionVersionCode = providers.gradleProperty("rpcs3x6VersionCode")
    .orElse(providers.environmentVariable("RPCS3X6_VERSION_CODE"))
    .getOrElse("1")
    .toInt()
require(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+").matches(companionVersionName)) {
    "RPCS3X6 versionName must use four numeric parts (for example 0.0.1.42)"
}
require(companionVersionCode > 0) { "RPCS3X6 versionCode must be positive" }
val releaseKeystorePath = providers.environmentVariable("KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("KEYSTORE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("KEYSTORE_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("KEYSTORE_KEY_PASSWORD").orNull
val hasReleaseKeystore = !releaseKeystorePath.isNullOrBlank() && file(releaseKeystorePath).isFile &&
    !releaseStorePassword.isNullOrBlank() && !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "net.rpcs3"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.teknogods.rpcs3x6"
        minSdk = 31
        targetSdk = 35
        versionCode = companionVersionCode
        versionName = companionVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a" /*, "x86_64" */)
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
            buildStagingDirectory = file(".cxx-rpcs3x6")
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        // This is necessary for libadrenotools custom driver loading
        jniLibs.useLegacyPackaging = true
    }
}

base.archivesName = "rpcs3x6"

dependencies {
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.ui.tooling.preview.android)
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity)
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")
    implementation("io.ktor:ktor-client-json:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-client-logging:3.0.3")
}
