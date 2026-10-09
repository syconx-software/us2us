plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(testFixtures(project(":core")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    applicationName = "us2us-bot"
    mainClass = "com.syconx.us2us.cli.MainKt"
}

tasks.test {
    // The live bot test depends on these; never reuse a result produced against another bot or relay.
    inputs.property("relayUrl", providers.environmentVariable("RELAY_URL").orElse(""))
    inputs.property("botState", providers.environmentVariable("US2US_BOT_STATE").orElse(""))
}
