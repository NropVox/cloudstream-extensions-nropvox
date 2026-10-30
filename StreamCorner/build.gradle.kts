version = 4

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Live sports and TV from streamcorner.st: NBA (live and full-game replays), MLB, NFL/CFB, soccer, all-sports schedules and 24/7 channels. " +
        "The site encrypts its catalog, so the plugin runs its decoder in a hidden WebView. Supports plain HLS, Amazon ClearKey DASH and NBA replay streams; links take a few seconds."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("Live")
    iconUrl = "https://streamcorner.st/apple-touch-icon.png"
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
