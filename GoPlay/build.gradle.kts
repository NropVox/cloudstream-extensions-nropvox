version = 3

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Korean, Chinese, Japanese and Thai dramas, movies and variety shows from GoPlay. Solves GoPlay's access check in a hidden WebView."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("AsianDrama", "Movie", "TvSeries")
    iconUrl = "https://assets.goplay.su/hearticon180.png"
}

// Cloudstream's inline JSON and key APIs use Java 11 bytecode.
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
}
