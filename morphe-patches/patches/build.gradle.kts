group = "app.morphe.patches.pixelcamera"
version = "1.0.6-9a.2"

patches {
    about {
        name = "Pixel Camera: Pixel 9a Studio"
        description = "Pixel 9a Fusion extension with Director and Single Take launchers for the Studio companion"
        source = "https://github.com/NotADeveloper4665/Pixel-Camera"
        author = "NotADeveloper4665; upstream patches by Akshay Kadam"
        contact = "na"
        website = "https://github.com/NotADeveloper4665/Pixel-Camera"
        license = "GPLv3"
    }
}

val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    compileOnly(libs.morphe.patcher)
    compileOnly(libs.smali)
    compileOnly("com.github.REAndroid:arsclib:a28c6fb2a7")
    patchListGeneratorClasspath(libs.gson)

    testImplementation(kotlin("test"))
    testImplementation(libs.morphe.patcher)
    testImplementation(libs.smali)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

tasks.named("jar") {
    finalizedBy("buildAndroid")
}

tasks.named("assemble") {
    dependsOn("buildAndroid")
}

