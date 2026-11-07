plugins { id("com.android.application") }
android {
    namespace = "dev.forcemind.xs.browser"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.forcemind.xs"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2-local-poc"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
dependencies {
    implementation(project(":xs-runtime"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
}
