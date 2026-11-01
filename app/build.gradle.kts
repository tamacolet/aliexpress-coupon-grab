plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tamacolet.coupongrab"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tamacolet.coupongrab"
        minSdk = 31
        targetSdk = 37
        versionCode = 3
        versionName = "1.2"
    }

    val releaseSigning = listOf(
        "CG_KEYSTORE_FILE", "CG_KEYSTORE_PASSWORD", "CG_KEY_ALIAS", "CG_KEY_PASSWORD"
    ).associateWith { System.getenv(it).orEmpty() }
    if (releaseSigning.values.all { it.isNotEmpty() }) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseSigning.getValue("CG_KEYSTORE_FILE"))
                storePassword = releaseSigning.getValue("CG_KEYSTORE_PASSWORD")
                keyAlias = releaseSigning.getValue("CG_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("CG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
