plugins { id("com.android.application") }
android {
    namespace = "dev.forcemind.xs.sharefixture"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.forcemind.xs.sharefixture"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1-fixture-only"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
