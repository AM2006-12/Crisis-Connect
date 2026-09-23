plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.crisisconnect"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.crisisconnect"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}
dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // Fragment + Lifecycle
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.9.4")
    implementation("androidx.lifecycle:lifecycle-livedata:2.9.4")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.7.2")
    annotationProcessor("androidx.room:room-compiler:2.7.2")

    // JetBrains annotations
    implementation("org.jetbrains:annotations:26.0.2")

    // JSON
    implementation("org.json:json:20250517")

    // Tests
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}