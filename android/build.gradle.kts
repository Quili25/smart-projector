import mx.quili.smartprojector.versioning.GitHistory

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

fun gitOutput(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { null }
}.getOrNull()

// Both apps are published together and version as one: they speak the same wire protocol, so
// shipping them out of step is the one thing that breaks mirroring (docs/VERSIONING.md).
val appVersionName: String = GitHistory.version { args -> gitOutput(*args.toTypedArray()) }.versionName

// versionCode must grow with every published build or Android refuses to install it as an
// update; the commit count is monotonic along staging -> main because promotes fast-forward.
val appVersionCode: Int = (
    providers.gradleProperty("smartcast.buildNumber").orNull
        ?: gitOutput("rev-list", "--count", "HEAD")
    )?.toIntOrNull() ?: 1
require(appVersionCode in 1..2_100_000_000) { "versionCode fuera de rango: $appVersionCode" }

val appCommit: String = providers.gradleProperty("smartcast.commit").orNull
    ?: gitOutput("rev-parse", "--short=12", "HEAD")
    ?: "unknown"
require(Regex("[0-9a-f]{7,40}|unknown").matches(appCommit)) { "Commit inválido: $appCommit" }

// LAN download page served by the Debian hub; scripts/Publish-Lan.ps1 writes into it.
val updateBaseUrl: String = providers.gradleProperty("smartcast.updateBaseUrl").orNull
    ?: "http://192.168.0.165:8088/smart-projector/"
require(Regex("""https?://[A-Za-z0-9.-]+(:\d{1,5})?(/[A-Za-z0-9._-]+)*/""").matches(updateBaseUrl)) {
    "smartcast.updateBaseUrl inválida (debe terminar en /): $updateBaseUrl"
}

extra["appVersionName"] = appVersionName
extra["appVersionCode"] = appVersionCode
extra["appCommit"] = appCommit
extra["updateBaseUrl"] = updateBaseUrl

// scripts/Publish-Lan.ps1 names the artifacts before Gradle runs; reading the version from
// here keeps the rules in one place instead of reimplementing them in PowerShell.
tasks.register("printVersion") {
    description = "Prints the version this build would produce."
    val name = appVersionName
    val code = appVersionCode
    doLast {
        println("name=$name")
        println("code=$code")
    }
}
