plugins {
    id("com.android.library")
}
android {
    namespace = "dev.forcemind.xs.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    sourceSets.getByName("main") {
        java.srcDirs("../src/main/java", "../src/android/java")
        manifest.srcFile("src/main/AndroidManifest.xml")
    }
}
