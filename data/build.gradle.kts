plugins { kotlin("jvm") }
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":domain")); implementation(project(":excel"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("com.google.crypto.tink:tink:1.24.0")
    implementation("io.github.willena:sqlite-jdbc:3.53.2.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(project(":pdf-worker"))
    testImplementation("org.apache.pdfbox:pdfbox:3.0.8")
}
tasks.test { systemProperty("phoneform.pdf.classpath", configurations.testRuntimeClasspath.get().asPath) }
