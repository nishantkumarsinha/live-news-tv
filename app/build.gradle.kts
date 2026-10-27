import java.util.Properties
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
}

// Build time stamp (IST), shown in the app. Pass -Pstamp=... to set it from the shell.
val stamp: String = (project.findProperty("stamp") as String?)
    ?: ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
        .format(DateTimeFormatter.ofPattern("yyyy.MM.dd.HH.mm"))

android {
    namespace = "com.nishvik.livenews"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.nishvik.livenews"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = stamp
        buildConfigField("String", "BUILD_STAMP", "\"$stamp\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Same package, two flavors. They differ only in the feed folder they read.
    // "stage" is the test TV (feed /test). Flavor names starting with "test" are avoided on purpose.
    flavorDimensions += "feed"
    productFlavors {
        create("stage") {
            dimension = "feed"
            buildConfigField("String", "FEED_URL", "\"https://livenewsindia.nishvik.com/test\"")
        }
        create("prod") {
            dimension = "feed"
            buildConfigField("String", "FEED_URL", "\"https://livenewsindia.nishvik.com/prod\"")
        }
    }

    // Release signing. The properties file path comes from livenews.keystoreProps (set in
    // ~/.gradle/gradle.properties, outside the repo), else keystore.properties in the project root.
    // Without a file the release build falls back to the debug key, so a fresh clone still builds.
    val keystoreProps = Properties().apply {
        val outside = (project.findProperty("livenews.keystoreProps") as String?)?.let { file(it) }
        val f = outside ?: rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            // Real release key when keystore.properties exists, otherwise the debug key.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // No runtime libraries: the screen is plain Android views.
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}