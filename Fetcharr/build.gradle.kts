version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Adds \"Send to Fetcharr\" and \"Send season to Fetcharr\" to the episode/movie action menu. " +
        "The chosen link, its headers and subtitles are handed to the Fetcharr Android app, which queues the " +
        "download on your Fetcharr server. Requires the Fetcharr app (com.istarvin.fetcharr). Torrent, magnet, " +
        "DRM and split-audio sources can't be sent."
    status = 1
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
    // Provided by the Cloudstream app at runtime (versions from its gradle/libs.versions.toml); needed to reach
    // the result screen's view model.
    compileOnly("androidx.core:core-ktx:1.18.0")
    compileOnly("androidx.fragment:fragment-ktx:1.8.9")
    compileOnly("androidx.lifecycle:lifecycle-livedata-ktx:2.10.0")
    compileOnly("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    testImplementation("junit:junit:4.13.2")
}
