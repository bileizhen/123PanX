plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}
val releaseSigningValues = listOf("PANX_SIGNING_STORE_FILE", "PANX_SIGNING_STORE_PASSWORD", "PANX_SIGNING_KEY_ALIAS", "PANX_SIGNING_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull }
val releaseSigningReady = releaseSigningValues.values.all { !it.isNullOrBlank() }
android {
    namespace = "io.github.bileizhen.pan123x"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.bileizhen.pan123x"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.4.2"
        testInstrumentationRunner = "io.github.bileizhen.pan123x.PanXTestRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        if (releaseSigningReady) create("officialRelease") {
            storeFile = file(releaseSigningValues.getValue("PANX_SIGNING_STORE_FILE")!!)
            storePassword = releaseSigningValues.getValue("PANX_SIGNING_STORE_PASSWORD")
            keyAlias = releaseSigningValues.getValue("PANX_SIGNING_KEY_ALIAS")
            keyPassword = releaseSigningValues.getValue("PANX_SIGNING_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("officialRelease")
        }
        getByName("debug") {
            // UI tests seed and clear Room: keep them separate from the user's installed client.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        encoding = "UTF-8"
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    packaging { resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/AL2.0", "META-INF/LGPL2.1") }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = true }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst { check(releaseSigningReady) { "Release signing requires all four PANX_SIGNING_* environment variables" } }
}
tasks.withType<Test>().configureEach {
    // Keep the Gradle worker protocol and Windows paths consistent with the build JVM.
    jvmArgs("-Dfile.encoding=UTF-8")
}
dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-navigation3:2.10.0")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-navigation3-ui-android:0.9.3")
    implementation("androidx.navigation3:navigation3-runtime:1.1.4")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    // M6 预览：视频/音频在线播放（ExoPlayer + PlayerView）。
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // M7 QR 登录：二维码**生成**（编码矩阵→Bitmap），纯 Java，不含扫码相机。
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.11.2")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.11.2")
}
