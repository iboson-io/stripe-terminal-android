import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("kotlin-kapt")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    val minSdkVersion: Int by project
    val latestSdkVersion: Int by project

    namespace = "com.stripe.example"
    compileSdk = latestSdkVersion

    // Ensure every built APK is signed so it installs when transferred (not just via USB).
    // Release uses debug keystore for sideload builds; use your own keystore for production.
    signingConfigs {
        getByName("debug") { /* default */ }
        create("release") {
            val home = System.getProperty("user.home")
            val debugKeystore = file("$home/.android/debug.keystore")
            if (debugKeystore.exists()) {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        minSdk = minSdkVersion
        targetSdk = latestSdkVersion

        val backendUrl = project.property("EXAMPLE_BACKEND_URL").toString().trim('"')
        buildConfigField("String", "EXAMPLE_BACKEND_URL", "\"$backendUrl\"")
        
        val locationId = project.property("LOCATION_ID").toString().trim('"').trim()
        buildConfigField("String", "STRIPE_LOCATION_ID", "\"$locationId\"")
        
        val useSimulatedReader = project.property("USE_SIMULATED_READER").toString().trim('"').toBoolean()
        buildConfigField("boolean", "USE_SIMULATED_READER", useSimulatedReader.toString())

        val carwashApiUrl = project.property("CARWASH_API_URL").toString().trim('"')
        buildConfigField("String", "CARWASH_API_URL", "\"$carwashApiUrl\"")

    }

    buildTypes {
        release {
            val debugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
            if (debugKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isShrinkResources = true
            isMinifyEnabled = true
        }
    }

    buildFeatures {
        dataBinding = true
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_1_8
        }
    }
}

// Force Kapt task to use Java 8. See https://youtrack.jetbrains.com/issue/KT-55947.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KaptGenerateStubs>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

val androidxLifecycleVersion = "2.6.2"
val kotlinCoroutinesVersion = "1.9.0"
val retrofitVersion = "2.11.0"
val stripeTerminalVersion = "5.1.1"

dependencies {
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.browser:browser:1.8.0")

    // ViewModel and LiveData
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$androidxLifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$androidxLifecycleVersion")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$kotlinCoroutinesVersion")

    // OK HTTP
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:$retrofitVersion")
    implementation("com.squareup.retrofit2:converter-gson:$retrofitVersion")

    // Stripe Terminal library
    implementation("com.stripe:stripeterminal-taptopay:$stripeTerminalVersion")
    implementation("com.stripe:stripeterminal-core:$stripeTerminalVersion")
    implementation("com.stripe:stripeterminal-ktx:$stripeTerminalVersion")
    
    // Lottie animation
    implementation("com.airbnb.android:lottie:6.1.0")

    // Timber logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Firebase (BOM manages all versions)
    implementation(platform("com.google.firebase:firebase-bom:34.2.0"))
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-analytics")

    // Leak canary
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}
