plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation("io.github.flameyossnowy:turboscanner:1.4.1")

    jmhImplementation("com.google.code.gson:gson:2.11.0")
    jmhImplementation("tools.jackson.core:jackson-databind:3.0.0")
    jmhImplementation("com.dslplatform:dsl-json:2.0.2")

    testAnnotationProcessor(project(":annotation-processor"))
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    compileOnly("org.jetbrains:annotations:26.0.2")
}

tasks.test {
    useJUnitPlatform()
}