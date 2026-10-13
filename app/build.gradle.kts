plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.intellitrip"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.intellitrip"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }

    // Shrinking is on only for release. The rules live in proguard-rules.pro and are
    // deliberately conservative: the Atlas modules use no reflection, so every entry
    // there exists for MapLibre's native layer, Hilt's generated code or Retrofit's
    // generic signatures.
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // No signing config is wired yet. A release build is produced unsigned on
            // purpose: signing keys are handled separately and a debug-signed artifact
            // must never be mistaken for a release one.
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
    kotlinOptions { jvmTarget = "17" }
    compileOptions {
        // Core library desugaring, enabled deliberately rather than to fix one
        // occurrence. `java.time` is available from API 26, but the wider `java.time`
        // surface is not: `LocalDate.ofInstant`, for instance, needs API 34 and throws
        // NoSuchMethodError at runtime on an older device. Two such bugs already
        // shipped this way — `Files.readString` in the GTFS and basemap stores, caught
        // only by the instrumented suite — and lint surfaces them one at a time, which
        // is the worst possible time to find out.
        //
        // Desugaring makes the JDK API surface available back to minSdk, so the whole
        // class of defect stops existing rather than being rediscovered per call site.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(project(":core:atlas-domain"))
    implementation(project(":core:atlas-contracts"))
    implementation(project(":core:atlas-gtfs-realtime"))
    implementation(project(":core:atlas-gtfs-static"))
    implementation(project(":core:atlas-map"))
    implementation(project(":core:atlas-map-android"))
    implementation(project(":core:atlas-weather"))
    implementation(project(":core:atlas-scope"))
    implementation(project(":core:atlas-offline"))
    implementation(project(":core:atlas-routing"))
    implementation(project(":core:atlas-engine"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.03"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
