import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

android {
    namespace = "com.ridestr.common"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Compose (for shared UI theme)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.compose.material:material-icons-extended:1.7.6")

    // Valhalla Mobile - Offline routing engine
    implementation(libs.valhalla.mobile)
    implementation(libs.valhalla.models)
    implementation(libs.valhalla.models.config)
    implementation(libs.osrm.api)
    implementation(libs.moshi.kotlin)

    // Nostr - Quartz library
    api(libs.quartz)
    implementation(libs.security.crypto)

    // Cashu - ecash payments (NUT-14 HTLC for escrow)
    implementation(libs.cdk.kotlin)

    // Accompanist - Compose utilities
    implementation(libs.accompanist.permissions)

    // Networking
    implementation(libs.okhttp)

    // Image Loading
    api(libs.coil.compose)

    // QR Code generation
    implementation("com.google.zxing:core:3.5.3")

    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.11.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
