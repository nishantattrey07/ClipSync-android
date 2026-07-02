import java.security.MessageDigest
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

val canonicalFixture = rootProject.layout.projectDirectory.file(
    "../ClipSync/protocol/v1/fixtures.json"
)
val canonicalChecksums = rootProject.layout.projectDirectory.file(
    "../ClipSync/protocol/v1/SHA256SUMS"
)
val verifiedFixtureDirectory = layout.buildDirectory.dir("private-protocol-fixtures")
val hasPrivateUnitTests = layout.projectDirectory.dir("src/testPrivateProtocol").asFile.isDirectory
val hasPrivateAndroidTests = layout.projectDirectory.dir("src/androidTestPrivateProtocol").asFile.isDirectory

android {
    namespace = "com.nishantattrey.clipsync.core.protocol"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release { }
        create("privateProtocol") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
        }
    }

    testBuildType = "privateProtocol"

    sourceSets {
        maybeCreate("androidTestPrivateProtocol").assets.directories.add(
            verifiedFixtureDirectory.get().asFile.absolutePath
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.argon2kt)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
}

val verifyPrivateProtocolFixture by tasks.registering {
    group = "verification"
    description = "Verifies the private V1 fixture checksum before fixture content can be consumed."
    val inputFixture = canonicalFixture
    val inputChecksums = canonicalChecksums
    val outputFixture = verifiedFixtureDirectory.map { it.file("fixtures.json") }
    
    inputs.files(inputFixture, inputChecksums)
    outputs.file(outputFixture)

    doLast {
        val fixtureFile = inputFixture.asFile
        val checksumFile = inputChecksums.asFile
        check(fixtureFile.isFile && checksumFile.isFile) {
            "Private protocol fixtures are unavailable. Expected fixtures.json and SHA256SUMS in the canonical sibling repository."
        }

        val checksumLine = checksumFile.useLines { lines ->
            lines.firstOrNull { it.isNotBlank() }
        } ?: error("Private protocol checksum file is empty.")
        val expected = checksumLine.trim().substringBefore(' ')
        check(expected.matches(Regex("[0-9a-f]{64}"))) {
            "Private protocol checksum is malformed."
        }

        val digest = MessageDigest.getInstance("SHA-256")
        fixtureFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) {
            "Private protocol fixture checksum mismatch. Expected $expected but found $actual."
        }

        val output = outputFixture.get().asFile
        requireNotNull(output.parentFile).mkdirs()
        fixtureFile.copyTo(output, overwrite = true)
    }
}

tasks.configureEach {
    if (name == "testPrivateProtocolUnitTest" && hasPrivateUnitTests) {
        dependsOn(verifyPrivateProtocolFixture)
    }
    if (name == "connectedPrivateProtocolAndroidTest" && hasPrivateAndroidTests) {
        dependsOn(verifyPrivateProtocolFixture)
    }
    if (name == "mergePrivateProtocolAndroidTestAssets" && hasPrivateAndroidTests) {
        dependsOn(verifyPrivateProtocolFixture)
    }
}

tasks.withType<Test>().configureEach {
    if (name == "testPrivateProtocolUnitTest" && hasPrivateUnitTests) {
        systemProperty(
            "clipsync.private.fixture",
            verifiedFixtureDirectory.get().file("fixtures.json").asFile.absolutePath,
        )
        systemProperty(
            "clipsync.private.report",
            rootProject.layout.projectDirectory.file(
                ".private/compatibility-reports/protocol-unit.json",
            ).asFile.absolutePath,
        )
    }
}
