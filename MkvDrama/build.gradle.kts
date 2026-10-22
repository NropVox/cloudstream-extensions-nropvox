version = 4

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Asian dramas and movies from MKVDrama. Standalone download-panel and container resolution; some mirrors require browser verification."
    status = 3
    tvTypes = listOf("AsianDrama", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=mkvdrama.net&sz=%size%"
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
