import java.util.Properties

val testAdMobAppId = "ca-app-pub-3940256099942544~3347511713"
val testInterstitialAdUnitId = "ca-app-pub-3940256099942544/1033173712"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingPropertiesFile = rootProject.file(".signing/keystore.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.exists()) signingPropertiesFile.inputStream().use(::load)
}
val adMobAppId = providers.gradleProperty("ALADIN_ADMOB_APP_ID").orNull
val interstitialAdUnitId = providers.gradleProperty("ALADIN_INTERSTITIAL_AD_UNIT_ID").orNull
val monetizationValues = listOf(adMobAppId, interstitialAdUnitId)
val monetizationConfigured = adMobAppId?.matches(Regex("^ca-app-pub-\\d{16}~\\d{10}$")) == true &&
    interstitialAdUnitId?.matches(Regex("^ca-app-pub-\\d{16}/\\d{10}$")) == true
require(monetizationValues.all { it == null } || monetizationConfigured) {
    "AdMob app and interstitial IDs must both be valid or both be absent."
}

android {
    namespace = "cz.majkey.pocasicesko"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.majkeylab.weatheraladin"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "0.2.0-beta.5"

        manifestPlaceholders["adMobAppId"] = adMobAppId ?: testAdMobAppId
        buildConfigField("boolean", "MONETIZATION_CONFIGURED", monetizationConfigured.toString())
        buildConfigField(
            "String",
            "INTERSTITIAL_AD_UNIT_ID",
            "\"${interstitialAdUnitId ?: testInterstitialAdUnitId}\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            manifestPlaceholders["adMobAppId"] = testAdMobAppId
            buildConfigField("boolean", "MONETIZATION_CONFIGURED", "true")
            buildConfigField("String", "INTERSTITIAL_AD_UNIT_ID", "\"$testInterstitialAdUnitId\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (signingPropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes.named("release") {
        signingConfig = signingConfigs.findByName("release")
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.fragment:fragment:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.shredzone.commons:commons-suncalc:3.11")
    implementation("com.android.billingclient:billing-ktx:9.1.0")
    implementation("com.google.android.gms:play-services-ads:25.4.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
