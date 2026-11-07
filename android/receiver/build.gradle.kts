import mx.quili.smartprojector.build.LanUpdates
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val updateManifestUrl = LanUpdates.manifestUrl(rootProject.extra["updateBaseUrl"] as String, "receiver")
LanUpdates.requireCleartextAllowed(updateManifestUrl, file("src/main/res/xml/network_security_config.xml"))

// Release signing: keystore.properties (gitignored) or environment variables on the hub.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)

val releaseStoreFile = signingValue("storeFile", "SMARTCAST_KEYSTORE_FILE")

android {
    namespace = "mx.quili.smartprojector.receiver"
    compileSdk = 36

    defaultConfig {
        applicationId = "mx.quili.smartprojector.receiver"
        // The projector runs Android 11 (API 30); 24 keeps the door open for older
        // Android TV boxes without costing us any API we need.
        minSdk = 24
        targetSdk = 35
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
        buildConfigField("String", "GIT_COMMIT", "\"${rootProject.extra["appCommit"]}\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "SMARTCAST_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SMARTCAST_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SMARTCAST_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // A debug build carries a different signature, so the release APK on the LAN page
            // could never install over it. Separate the ids and silence the update check
            // instead of offering a download that Android would reject.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
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
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "kotlin/**")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":updates"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.android)
}
