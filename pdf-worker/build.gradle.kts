plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
application { mainClass.set("com.phoneform.desktop.pdfworker.MainKt") }
dependencies { implementation("org.apache.pdfbox:pdfbox:3.0.8") }
