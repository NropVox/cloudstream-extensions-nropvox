version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Hentai series from hentaverse.com. First-party HLS up to 1080p with MP4 renditions and VTT subtitles."
    status = 1
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=hentaverse.com&sz=%size%"
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
