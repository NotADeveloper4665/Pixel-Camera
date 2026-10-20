plugins { id("com.android.application") }
android {
    namespace = "org.eclipse.pixelcamera.studio"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.eclipse.pixelcamera.studio"
        minSdk = 35
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-9a"
    }
    sourceSets["main"].java.srcDir("../app/src/main/java")
    buildFeatures { buildConfig = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    testImplementation("junit:junit:4.13.2")
}
