version = 2

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Live sports and TV from streamcorner.st: NBA (live and full-game replays), MLB, NFL/CFB, soccer, all-sports schedules and 24/7 channels. " +
        "The site encrypts its catalog, so the plugin runs the site's own decoder in a hidden WebView and opens each player there to find its HLS stream; links take a few seconds."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=streamcorner.st&sz=%size%"
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
