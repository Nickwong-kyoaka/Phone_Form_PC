plugins {
    kotlin("jvm") version "2.1.20" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}
allprojects { group = "com.phoneform.desktop"; version = "0.3.0-dev" }
subprojects {
    tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
    tasks.withType<Test>().configureEach {
        maxHeapSize = "2g"
        testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}
