version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Hentai from hentaimama.io. Direct MP4 (1080p/720p/480p) and HLS mirrors."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=hentaimama.io&sz=%size%"
}

// Cloudstream's inline JSON APIs use Java 11 bytecode.
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
}
