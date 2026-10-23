version = 2

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Anime from reanime.to. Sub and dub HLS streams up to 1080p from the site's flixcloud player (HD-1 and HD-2 servers), with soft subtitles in many languages."
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    iconUrl = "https://www.google.com/s2/favicons?domain=reanime.to&sz=%size%"
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
