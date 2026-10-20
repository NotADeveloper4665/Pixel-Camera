plugins { id("com.android.application") }

android {
    namespace = "org.eclipse.pixelcamera.fusion"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.eclipse.pixelcamera.pixel9a"
        minSdk = 35
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-9a"
    }
    buildFeatures { buildConfig = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
}
dependencies { testImplementation("junit:junit:4.13.2") }

tasks.register<Copy>("packageNexusTestApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("outputs/test-apk"))
    rename { "Nexus Camera Fusion.apk" }
}
