plugins {
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
}

val specDir = rootProject.layout.projectDirectory.dir("../spec")

sourceSets {
    main {
        // The schema is shared with ios/Us2usKit; generate from the single source of truth.
        proto { srcDir(specDir.dir("proto")) }
    }
}

dependencies {
    // The Android app replaces this with org.signal:libsignal-android (see README.md).
    api(libs.libsignal.client)
    api(libs.protobuf.javalite)
    api(libs.protobuf.kotlin.lite)
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // FakeRelay and TestClient, shared with the cli module's tests.
    testFixturesApi(libs.okhttp.mockwebserver)
    testFixturesImplementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

protobuf {
    protoc { artifact = libs.protoc.get().toString() }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") { option("lite") }
                register("kotlin") { option("lite") }
            }
        }
    }
}

tasks.processResources {
    // Bundled in both apps; one copy of each list, taken from spec/ at build time
    // (PROTOCOL.md §5.2 secret codes, §5.5 backup passwords).
    from(specDir) {
        include("common-secret-codes.txt", "common-backup-passwords.txt")
        into("com/syconx/us2us/core/vault")
    }
}

tasks.test {
    systemProperty("us2us.specDir", specDir.asFile.absolutePath)
    // The live relay test runs only when RELAY_URL is set; pass it through explicitly so the
    // build cache never reuses a result produced against a different relay.
    inputs.property("relayUrl", providers.environmentVariable("RELAY_URL").orElse(""))
    // The Swift implementation's vectors are verified here: a change there must rerun the tests.
    inputs.files(specDir.dir("test-vectors/swift").asFileTree).withPropertyName("swiftVectors").withPathSensitivity(PathSensitivity.RELATIVE)
    // The username case table shared with the iOS app.
    inputs.file(specDir.file("username-rules.json")).withPropertyName("usernameRules").withPathSensitivity(PathSensitivity.RELATIVE)
    // Test vectors are written by the tests themselves; never serve them from the build cache.
    outputs.cacheIf { false }
}

val generateTestVectors by tasks.registering(Test::class) {
    description = "Regenerates spec/test-vectors/kotlin/*.json from this implementation."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("us2us.specDir", specDir.asFile.absolutePath)
    filter { includeTestsMatching("com.syconx.us2us.core.vectors.TestVectorGeneratorTest") }
    outputs.upToDateWhen { false }
}
