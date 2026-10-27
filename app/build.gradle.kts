import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val releaseStore = localProps.getProperty("sii.release.storeFile")

android {
    namespace = "com.sii.browser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sii.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        resourceConfigurations += listOf("zh", "en")
        resValue("string", "app_package_name", "com.sii.browser")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = rootProject.file(releaseStore)
                storePassword = localProps.getProperty("sii.release.storePassword")
                keyAlias = localProps.getProperty("sii.release.keyAlias")
                keyPassword = localProps.getProperty("sii.release.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 调试包与正式版共存，互不覆盖。
            applicationIdSuffix = ".debug"
            resValue("string", "app_package_name", "com.sii.browser.debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (releaseStore != null) signingConfigs.getByName("release") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
}
