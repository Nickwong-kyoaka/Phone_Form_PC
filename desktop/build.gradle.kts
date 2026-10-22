import org.jetbrains.compose.desktop.application.dsl.TargetFormat
plugins { kotlin("jvm"); id("org.jetbrains.compose"); id("org.jetbrains.kotlin.plugin.compose") }
val stageRuntimeResources by tasks.registering(Sync::class) {
    from(rootProject.file("runtime"))
    into(layout.buildDirectory.dir("stagedRuntime/common"))
}
// Compose strips native commands from jlink by default. The isolated PDF worker
// needs a Java launcher from the same JDK as its bundled runtime.
val retainPdfJavaLauncher by tasks.registering(Copy::class) {
    dependsOn(tasks.matching { it.name == "createRuntimeImage" })
    from(file(System.getProperty("java.home")).resolve("bin/java.exe"))
    into(layout.buildDirectory.dir("compose/tmp/main/runtime/bin"))
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":domain")); implementation(project(":excel")); implementation(project(":data"))
    implementation(project(":vision")); implementation(project(":pdf-worker"))
    implementation(compose.desktop.currentOs); implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("com.google.code.gson:gson:2.13.1")
    testImplementation("junit:junit:4.13.2")
}
compose.desktop {
    application {
        mainClass = "com.phoneform.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "PhoneForm AI PC"
            packageVersion = "0.3.2"
            description = "Offline questionnaire review and Excel export"
            vendor = "PhoneForm AI"
            modules("java.sql", "java.naming", "java.desktop", "java.xml", "jdk.unsupported", "jdk.crypto.ec")
            appResourcesRootDir.set(layout.buildDirectory.dir("stagedRuntime"))
            windows { menuGroup = "PhoneForm AI"; shortcut = true; upgradeUuid = "ed691b8d-e474-4c05-ac56-d0ce19f69f28" }
        }
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(stageRuntimeResources) }
tasks.matching { it.name == "createDistributable" || it.name == "packageMsi" }.configureEach { dependsOn(retainPdfJavaLauncher) }
