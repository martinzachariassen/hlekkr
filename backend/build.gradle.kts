import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.shadow)
}

group = "no.mlz"
version = "1.0.0"

application {
    mainClass.set("no.mlz.shortener.ApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.swagger.ui)

    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

// Shadow, not Ktor's buildFatJar: Flyway's SPI registry is split across flyway-core and
// flyway-database-postgresql at the same resource path, and Shadow 9's default EXCLUDE strategy
// discards one half before mergeServiceFiles() can concatenate them — Flyway then NPEs at startup.
// INCLUDE lets the merge actually run.
tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    manifest {
        attributes("Main-Class" to application.mainClass.get())
    }
}

// A dropped SPI half only surfaces in the deployed jar — every classpath-based test still passes —
// so guard the packaged artifact and fail the build on a merge regression, not production.
val verifyFlywayServiceMerge by tasks.registering {
    val jarFile = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(jarFile)
    doLast {
        val entry = "META-INF/services/org.flywaydb.core.extensibility.Plugin"
        val plugins = ZipFile(jarFile.get().asFile).use { zip ->
            val e = requireNotNull(zip.getEntry(entry)) { "Flyway SPI registry missing from the shadow jar" }
            zip.getInputStream(e).bufferedReader().readLines().filter { it.isNotBlank() }
        }
        val hasCore = plugins.any { it.startsWith("org.flywaydb.core.") }
        val hasPostgres = plugins.any { it.startsWith("org.flywaydb.database.postgresql.") }
        check(hasCore && hasPostgres) {
            "Flyway SPI registry incomplete after shadow merge (core=$hasCore postgres=$hasPostgres, " +
                "${plugins.size} entries). Keep shadowJar's duplicatesStrategy=INCLUDE so mergeServiceFiles " +
                "concatenates both registries — otherwise migrations NPE at startup."
        }
        logger.lifecycle("Flyway SPI registry OK: ${plugins.size} plugins merged.")
    }
}

tasks.check {
    dependsOn(verifyFlywayServiceMerge)
}
