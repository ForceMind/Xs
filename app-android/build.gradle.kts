plugins { id("com.android.application") }
val xsSigningStore = providers.environmentVariable("XS_SIGNING_STORE_FILE").orNull
val xsSigningStorePassword = providers.environmentVariable("XS_SIGNING_STORE_PASSWORD").orNull
val xsSigningAlias = providers.environmentVariable("XS_SIGNING_KEY_ALIAS").orNull
val xsSigningKeyPassword = providers.environmentVariable("XS_SIGNING_KEY_PASSWORD").orNull
val xsSigningConfigured = listOf(xsSigningStore,xsSigningStorePassword,xsSigningAlias,xsSigningKeyPassword).all { !it.isNullOrBlank() }
val xsVersionCodeInput = providers.gradleProperty("xsVersionCode").orNull
val xsVersionCode = if(xsVersionCodeInput==null)3 else xsVersionCodeInput.toIntOrNull()?.takeIf { it > 0 }
    ?: throw GradleException("xsVersionCode must be a positive 32-bit integer")
android {
    namespace = "dev.forcemind.xs.browser"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.forcemind.xs"
        minSdk = 26
        targetSdk = 35
        versionCode = xsVersionCode
        versionName = "0.3-reliability-poc"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        if (xsSigningConfigured) create("xsRelease") {
            storeFile = file(xsSigningStore!!)
            storePassword = xsSigningStorePassword
            keyAlias = xsSigningAlias
            keyPassword = xsSigningKeyPassword
            enableV1Signing = true
            enableV2Signing = true
        }
    }
    buildTypes {
        getByName("release") {
            if (xsSigningConfigured) signingConfig = signingConfigs.getByName("xsRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
// A release candidate must never silently fall back to an unsigned or ephemeral-debug APK.
gradle.taskGraph.whenReady {
    if (allTasks.any { it.project == project && it.name.contains("Release") &&
            (it.name.startsWith("assemble") || it.name.startsWith("bundle") || it.name.startsWith("package") || it.name.startsWith("validateSigning")) }) {
        check(xsSigningConfigured) { "Release candidate requires all XS_SIGNING_* signing inputs; no unsigned/debug fallback is allowed" }
        check(file(xsSigningStore!!).isFile) { "Configured release keystore is unavailable" }
    }
}
dependencies {
    implementation(project(":xs-runtime"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
}
