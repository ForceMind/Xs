plugins {
    id("com.android.application")
}
android {
    namespace = "dev.forcemind.xs.harness"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.forcemind.xs.harness"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-dev"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
dependencies {
    implementation(project(":xs-runtime"))
}
