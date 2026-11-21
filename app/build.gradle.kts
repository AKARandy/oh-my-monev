import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing is driven by keystore.properties (gitignored, lives in the repo root).
// Missing file -> release build is unsigned (safe for CI/check-only).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "id.ohmymonev.android"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "id.ohmymonev.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "1.1.0"
        // Ponsel nyata semuanya ARM64; ABI lain dibuang sesuai keputusan pemilik.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    packaging {
        jniLibs {
            // Penjamin ganda: pustaka ORT untuk ABI non-arm64 tidak boleh ikut terpaket.
            excludes += listOf("**/armeabi-v7a/**", "**/x86/**", "**/x86_64/**")
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "Oh My Monev Dev")
        }
        release {
            isMinifyEnabled = false
            if (providers.gradleProperty("useExistingDistributionKey").orNull == "true") {
                // Keep upgrade compatibility with the APKs previously distributed on GitHub.
                signingConfig = signingConfigs.getByName("debug")
            } else if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach {
            it.versionCode.set(24)
            it.versionName.set("1.1.0-dev")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation("androidx.webkit:webkit:1.12.1")
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Mesin neural mode Otomatis: runtime ONNX di perangkat.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    debugImplementation(libs.compose.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // JVM screenshot tests (visual proof without an emulator)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
}

tasks.withType<Test>().configureEach {
    systemProperty("roborazzi.test.record", "true")
}
