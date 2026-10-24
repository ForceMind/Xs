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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
dependencies {
    implementation(project(":xs-runtime"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
