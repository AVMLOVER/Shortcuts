import java.util.Properties
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Private signing data lives outside the checkout and is never committed.
val releaseSigningPath = providers.environmentVariable("SHORTCUTS_SIGNING_PROPERTIES").orNull
val releaseSigning = Properties().also { properties ->
    releaseSigningPath?.let { path -> File(path).inputStream().use(properties::load) }
}
android {
    namespace = "com.germanium.shortcuts"
    compileSdk = 37
    ndkVersion = "30.0.16248370"
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/rustJniLibs").get().asFile)
    defaultConfig {
        applicationId = "com.germanium.shortcuts"
        minSdk = 26
        targetSdk = 37
        versionCode = 10005
        versionName = "1.0.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testApplicationId = providers.gradleProperty("shortcuts.testApplicationId").orElse("com.germanium.shortcuts.test").get()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; aidl = true; buildConfig = true }
    signingConfigs {
        if (releaseSigningPath != null) create("release") {
            fun required(name: String) = releaseSigning.getProperty(name)?.takeIf { it.isNotBlank() } ?: error("Missing release signing property: $name")
            val keyFile = File(required("storeFile"))
            storeFile = if (keyFile.isAbsolute) keyFile else File(File(releaseSigningPath).parentFile, keyFile.path)
            storeType = releaseSigning.getProperty("storeType", "PKCS12")
            storePassword = required("storePassword")
            keyAlias = required("keyAlias")
            keyPassword = required("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            if (releaseSigningPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}

val buildRust = tasks.register<Exec>("buildRust") {
    val nativeRoot = rootProject.file("math-rust")
    inputs.dir(nativeRoot.resolve("src"))
    inputs.file(nativeRoot.resolve("Cargo.toml"))
    inputs.file(nativeRoot.resolve("Cargo.lock"))
    inputs.dir(rootProject.file("tools"))
    val destination = layout.buildDirectory.dir("generated/rustJniLibs")
    outputs.dir(destination)
    val local = Properties().also { props -> rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) } }
    val sdkRoot = System.getenv("ANDROID_HOME") ?: local.getProperty("sdk.dir") ?: error("Set ANDROID_HOME or sdk.dir")
    val ndk = File(sdkRoot, "ndk/30.0.16248370").absolutePath
    if (System.getProperty("os.name").startsWith("Windows")) commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", rootProject.file("tools/build-native.ps1"), "-NdkHome", ndk, "-Output", destination.get().asFile)
    else commandLine("bash", rootProject.file("tools/build-native.sh"), ndk, destination.get().asFile)
}
tasks.named("preBuild").configure { dependsOn(buildRust) }
dependencies {
    implementation("com.squareup:gifencoder:0.10.1")
    implementation("com.github.bumptech.glide:gifdecoder:4.16.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation(project(":core"))
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.sharetarget:sharetarget:1.2.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.window:window:1.5.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
