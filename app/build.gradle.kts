import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.awesome.dhs.tools.downloader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.awesome.dhs.tools.downloader"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            val properties = Properties()
            val localPropertiesFile = rootProject.file("gradle.properties")
            if (localPropertiesFile.exists()) {
                properties.load(localPropertiesFile.inputStream())
            }
            if (properties.getProperty("RELEASE_STORE_FILE", "").isNullOrEmpty()) {
                return@create
            }

            storeFile = file(properties.getProperty("RELEASE_STORE_FILE", ""))
            storePassword = properties.getProperty("RELEASE_STORE_PASSWORD", "")
            keyAlias = properties.getProperty("RELEASE_KEY_ALIAS", "")
            keyPassword = properties.getProperty("RELEASE_KEY_PASSWORD", "")

        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
    }

    applicationVariants.all {
        val variant = this
        variant.assembleProvider.get().doLast {
            variant.outputs.forEach { output ->
                val apkFile = output.outputFile
                val destDir = project.rootDir.resolve(variant.buildType.name)
                val newName =
                    "${rootProject.name}-${variant.versionName}-${variant.buildType.name}.apk"

                copy {
                    from(apkFile)
                    into(destDir)
                    rename { newName }
                }
                println("Copied and renamed ${apkFile.name} to ${destDir.resolve(newName)}")
            }
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.accompanist.permissions)
    implementation(project(":titan-downloader"))
    implementation(libs.androidx.documentfile)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}