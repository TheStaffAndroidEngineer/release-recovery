plugins { alias(libs.plugins.androidApplication); alias(libs.plugins.kotlinCompose) }
android {
    namespace = "staff.android.release"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "staff.android.release"; minSdk = 26; targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "edition"
    productFlavors {
        create("legacy") {
            dimension = "edition"; versionCode = 2601; versionName = "26.1"; targetSdk = 35
            buildConfigField("int", "SCHEMA_VERSION", "1"); buildConfigField("String", "RELEASE_STAGE", "\"legacy\"")
        }
        create("candidate") {
            dimension = "edition"; versionCode = 2602; versionName = "26.2"
            buildConfigField("int", "SCHEMA_VERSION", "2"); buildConfigField("String", "RELEASE_STAGE", "\"candidate\"")
        }
        create("repair") {
            dimension = "edition"; versionCode = 2603; versionName = "26.3"
            buildConfigField("int", "SCHEMA_VERSION", "2"); buildConfigField("String", "RELEASE_STAGE", "\"repair\"")
        }
    }
    buildTypes {
        release {
            optimization { enable = true }
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_26; targetCompatibility = JavaVersion.VERSION_26 }
}
kotlin { jvmToolchain(27); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26) } }
dependencies {
    implementation(project(":release-core"))
    implementation(libs.serializationJson)
    implementation(libs.androidxLifecycleRuntimeCompose); implementation(libs.coroutinesAndroid)
    implementation(libs.androidxLifecycleViewModel)
    implementation(libs.androidxActivityCompose)
    implementation(platform(libs.composeBom))
    implementation(libs.composeUi); implementation(libs.composeFoundation); implementation(libs.composeMaterial3)
    androidTestImplementation(platform(libs.composeBom)); androidTestImplementation(libs.composeTestJunit4)
    androidTestImplementation(libs.androidxTestJunit); androidTestImplementation(libs.androidxTestRunner)
    androidTestImplementation(libs.espressoCore)
}
