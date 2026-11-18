import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing: keystore.properties at the repository root (gitignored) with storeFile, storePassword, keyAlias
// and keyPassword. Without it, release builds come out unsigned.
val releaseKeystore: Properties? = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
}

android {
    namespace = "my.fumui.sairitsu"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "my.fumui.sairitsu"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // Optimised even in debug builds (the ones the PC tools drive): at -O0 the benchmark
                // takes 4.4 s to parse on a test tablet, 0.7 s here. The fp flags in runtime/CMakeLists.txt
                // apply to every build type.
                arguments += listOf("-DSR_ANDROID=ON", "-DCMAKE_BUILD_TYPE=RelWithDebInfo")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../runtime/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        releaseKeystore?.let { props ->
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            externalNativeBuild {
                cmake {
                    // Logcat line per counted Play judgment (tag sairitsu_judge), debug builds only.
                    arguments += listOf("-DSR_JUDGMENT_LOG=ON")
                }
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
