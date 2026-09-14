import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }
val releaseVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val majorVersion = releaseVersion.getProperty("major").toInt()
val minorVersion = releaseVersion.getProperty("minor").toInt()
val stampVersion = releaseVersion.getProperty("build").toInt()
fun signingValue(name: String): String = providers.environmentVariable(name).orNull
    ?.takeIf { it.isNotBlank() } ?: error("Required release signing environment variable: $name")
android {
    namespace = "mba.robin.recruiser"
    compileSdk = 35
    ndkVersion = "27.0.12077973"
    defaultConfig {
        applicationId = "mba.robin.recruiser"
        minSdk = 26
        targetSdk = 35
        versionCode = majorVersion * 100000 + minorVersion
        versionName = "$majorVersion.$minorVersion.$stampVersion"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("production") {
            storeFile = file(signingValue("RECRUISER_KEYSTORE_PATH"))
            storePassword = signingValue("RECRUISER_KEYSTORE_PASSWORD")
            keyAlias = signingValue("RECRUISER_KEY_ALIAS")
            keyPassword = signingValue("RECRUISER_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("production")
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
    }
}
androidComponents { beforeVariants(selector().withBuildType("debug")) { it.enable = false } }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core"))
    implementation("com.google.ar:core:1.56.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
