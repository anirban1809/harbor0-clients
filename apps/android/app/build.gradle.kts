import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "app.harbor0.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.harbor0.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.1.18"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_URL", "\"https://zlh5nzxdy6.execute-api.us-east-1.amazonaws.com\"")
        // The web app, for pages this app can't show yet. Empty when the API is overridden.
        buildConfigField("String", "WEB_URL", "\"https://app.harbor0.com\"")
    }
    // -PharborApiUrl points a debug or profile build at another API, e.g. the local backend.
    fun com.android.build.api.dsl.ApplicationBuildType.endpointOverride() {
        val endpoint = providers.gradleProperty("harborApiUrl").orNull ?: return
        val uri = URI(endpoint)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in listOf("localhost", "127.0.0.1", "10.0.2.2")))
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
        buildConfigField("String", "API_URL", "\"${uri.toASCIIString()}\"")
        buildConfigField("String", "WEB_URL", "\"\"")
    }
    // The Play upload key, from ~/.gradle/gradle.properties (never this repository; see README "Release signing").
    // Release builds are signed with it when all four values are set, and stay unsigned otherwise.
    val upload = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
        .associateWith { providers.gradleProperty("HARBOR_UPLOAD_$it").orNull?.takeIf(String::isNotBlank) }
    if (upload.values.all { it != null }) signingConfigs.create("upload") {
        storeFile = file(upload.getValue("STORE_FILE")!!)
        storePassword = upload.getValue("STORE_PASSWORD")
        keyAlias = upload.getValue("KEY_ALIAS")
        keyPassword = upload.getValue("KEY_PASSWORD")
    } else if (upload.values.any { it != null })
        logger.warn("Release builds are unsigned: set ${upload.filterValues { it == null }.keys.joinToString { "HARBOR_UPLOAD_$it" }} in ~/.gradle/gradle.properties.")
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            endpointOverride()
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
        // Release speed for testing on a phone: optimized and not debuggable (a debug build scrolls noticeably worse),
        // but signed with the debug key under the debug app's id, so it installs over a debug build and keeps its sign-in.
        create("profile") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            matchingFallbacks += "release"
            endpointOverride()
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    // Argon2id for end-to-end encryption passphrases; the full provider, not Android's stripped copy.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    // QR codes for authenticator setup.
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
