version = 2

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Live sports and TV from zlive.st: ~250 channels (Sky Sports, TNT, beIN, DAZN, ESPN, NBA TV…) and today's live events (NBA, NHL, soccer, NFL, motorsport). HLS, mostly 1080p, with now/next EPG where the site has it."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=zlive.st&sz=%size%"

    isCrossPlatform = true
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
