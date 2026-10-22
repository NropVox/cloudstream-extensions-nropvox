version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Indian OTT web series (ULLU, NeonX, MoodX, Atrangii…) and adult films from XPrimeHub. Streams V-Cloud, HubCloud and V-Drive mirrors; G-Direct and 10Gbps as download-only links."
    status = 1
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=xprimehub.pics&sz=%size%"
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
