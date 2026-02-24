import me.champeau.jmh.JMHTask

plugins {
    id("java")
    id("me.champeau.jmh") version "0.7.3"
}

group = "me.flame.uniform.json"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://jitpack.io/")
}

dependencies {
    implementation(project(":core"))
    implementation("com.github.FlameyosSnowy:TurboScanner:1.3.0")

    jmhImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    jmhImplementation("com.google.code.gson:gson:2.11.0")

    testAnnotationProcessor(project(":annotation-processor"))
    jmhAnnotationProcessor(project(":annotation-processor"))

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("org.openjdk.jmh:jmh-core:1.37")
    testAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")

    compileOnly("org.jetbrains:annotations:26.0.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jmh {
    jvmArgs.set(listOf(
        "--add-modules", "jdk.incubator.vector",
        "-Duniform.generatedModule=UniformGeneratedJsonModuleJmh"
    ))
}

tasks.register<JMHTask>("jmhPrettyPrint") {
    dependsOn("jmhJar")

    jvmArgs.set(listOf(
        "--add-modules", "jdk.incubator.vector",
        "-Duniform.generatedModule=UniformGeneratedJsonModuleJmh"
    ))
    includes.set(listOf("me.flame.uniform.json.bench.JsonPrettyPrintBenchmark"))
    jarArchive.set(tasks.named<Jar>("jmhJar").flatMap { it.archiveFile })
    resultsFile.set(layout.buildDirectory.file("results/jmh/prettyPrint.json"))
}

tasks.named<JavaCompile>("compileJmhJava") {
    options.compilerArgs.addAll(
        listOf("-Auniform.generatedModule=UniformGeneratedJsonModuleJmh")
    )
}
