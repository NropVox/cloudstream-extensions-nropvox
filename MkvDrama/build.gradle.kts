version = 5

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Asian dramas and movies from MKVDrama. Standalone download-panel and container resolution; some mirrors require browser verification."
    status = 3
    tvTypes = listOf("AsianDrama", "Movie")
    iconUrl = "https://mkvdrama.net/static/assets/favicon/favicon.svg"

    requiresResources = true
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
    implementation("com.google.android.material:material:1.13.0")
    testImplementation("junit:junit:4.13.2")
}
