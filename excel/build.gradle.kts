plugins { kotlin("jvm") }
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { implementation(project(":domain")); testImplementation("junit:junit:4.13.2") }
