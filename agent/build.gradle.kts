import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("dev.jano.wake.agent.MainKt")
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.jna)
    implementation(libs.jna.platform)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

tasks.register<Jar>("agentJar") {
    archiveFileName.set("wake-agent.jar")
    manifest { attributes["Main-Class"] = "dev.jano.wake.agent.MainKt" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
}

// Self-contained Windows agent: WakeAgent.exe plus a trimmed Java runtime (no JDK needed on the PC).
// jdk.crypto.ec holds X25519 on Java 21; jdeps can't see it because it's loaded as a security provider.
val agentImageDir = layout.buildDirectory.dir("agent-image")
val agentImage = tasks.register<Exec>("agentImage") {
    dependsOn("agentJar")
    val jar = layout.buildDirectory.file("libs/wake-agent.jar")
    inputs.file(jar)
    inputs.file("jpackage/WakeSignIn.properties")
    outputs.dir(agentImageDir)
    doFirst {
        delete(agentImageDir)
        val input = layout.buildDirectory.dir("jpackage-input").get().asFile
        delete(input)
        input.mkdirs()
        jar.get().asFile.copyTo(input.resolve("wake-agent.jar"))
    }
    val jpackage = File(System.getProperty("java.home"), "bin/jpackage" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
    commandLine(
        jpackage.path, "--type", "app-image",
        "--name", "WakeAgent",
        "--app-version", "1.0.0",
        "--vendor", "Wake",
        "--description", "Wake agent: shut down, reboot, sleep and lock this PC from your phone",
        "--input", layout.buildDirectory.dir("jpackage-input").get().asFile.path,
        "--main-jar", "wake-agent.jar",
        "--main-class", "dev.jano.wake.agent.MainKt",
        // Second launcher on the same runtime: the pre-login sign-in program, started by a startup task.
        "--add-launcher", "WakeSignIn=" + file("jpackage/WakeSignIn.properties").path,
        "--add-modules", "java.base,java.desktop,java.logging,jdk.httpserver,jdk.crypto.ec",
        "--jlink-options", "--strip-debug --no-header-files --no-man-pages",
        "--dest", agentImageDir.get().asFile.path,
    )
}

// Built by credential-provider\build.cmd; WAKE_CP_BUILD moves its output (Controlled Folder Access).
val unlockDll = rootProject.file(
    (System.getenv("WAKE_CP_BUILD") ?: "credential-provider/build") + "/Release/WakeCredentialProvider.dll",
)

// The download: unzip, double-click "Install Wake Agent.cmd".
tasks.register<Zip>("agentZip") {
    dependsOn(agentImage)
    doFirst {
        check(unlockDll.exists()) { "Build the unlock module first: credential-provider\\build.cmd" }
    }
    archiveFileName.set("WakeAgent-Windows.zip")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("release"))
    into("WakeAgent-Windows") {
        from(agentImageDir)
        from(rootProject.file("Install Wake Agent.cmd"))
        from(rootProject.file("Uninstall Wake Agent.cmd"))
        into("pc-setup") { from(rootProject.file("pc-setup/setup.ps1")) }
        into("unlock") { from(unlockDll) }
    }
}
