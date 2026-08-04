plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mailsearch.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mailsearch.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // The OAuth redirect the Azure app registration must list. Changing it
        // here changes the manifest placeholder and the value sent to Microsoft
        // together, so the two can never drift apart.
        manifestPlaceholders["authScheme"] = "mailsearch"
        buildConfigField("String", "AUTH_REDIRECT", "\"mailsearch://auth\"")
    }

    signingConfigs {
        // A sideloaded APK still has to be signed or Android refuses to install
        // it. Point MAILSEARCH_KEYSTORE at a real keystore to sign with your own
        // key; without one the build falls back to the local debug key, which is
        // fine for a personal install but means a rebuild on another machine
        // will not update over it (uninstall first).
        create("sideload") {
            val keystore = System.getenv("MAILSEARCH_KEYSTORE")
            if (!keystore.isNullOrEmpty()) {
                storeFile = file(keystore)
                storePassword = System.getenv("MAILSEARCH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MAILSEARCH_KEY_ALIAS") ?: "mailsearch"
                keyPassword = System.getenv("MAILSEARCH_KEY_PASSWORD")
                    ?: System.getenv("MAILSEARCH_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("MAILSEARCH_KEYSTORE").isNullOrEmpty()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("sideload")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // Chrome Custom Tabs: sign-in happens in the real browser, so the app never
    // sees the password and the system's saved logins still work.
    implementation("androidx.browser:browser:1.8.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // org.json ships with Android but is stubbed out on the JVM, so the unit
    // tests need a real implementation.
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
