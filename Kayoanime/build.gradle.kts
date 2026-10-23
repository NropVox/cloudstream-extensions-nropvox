version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Anime from kayoanime.com: HEVC 1080p Dual Audio / Eng Sub releases hosted on Google Drive. " +
        "Files are played through your own Google Drive proxy (Cloudflare Worker); set its API key in the plugin settings. " +
        "Posts whose Drive folders are dead or only hold archives show no episodes."
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie")
    iconUrl = "https://www.google.com/s2/favicons?domain=kayoanime.com&sz=%size%"
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
