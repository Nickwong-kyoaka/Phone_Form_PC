pluginManagement { repositories { gradlePluginPortal(); google(); mavenCentral() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "PhoneForm-PC"
include(":domain", ":excel", ":data", ":vision", ":pdf-worker", ":desktop")
