version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Censored, uncensored, amateur and subtitled JAV from SupJav, up to 1080p HLS. Plays the site's Vidara, EarnVids, LuluStream and VOE servers (older posts: FC2Stream, StreamTape). Requires the Extractors plugin for all but VOE and StreamTape."
    status = 1
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=supjav.com&sz=%size%"
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
