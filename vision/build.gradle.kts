plugins { kotlin("jvm") }
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":domain"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // 5.20.0 has a reproduced Windows native regression: upstream issue #286.
    implementation("net.sourceforge.tess4j:tess4j:5.19.0") { exclude(group = "org.apache.pdfbox") }
    implementation(files(rootProject.file("runtime/opencv/opencv-4120.jar")))
    testImplementation("junit:junit:4.13.2")
}
