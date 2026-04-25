plugins {
    `java-library`
    signing
    application
    id("me.champeau.jmh") version "0.7.3"
    id("com.vanniktech.maven.publish") version "0.33.0"
}

group = "io.github.flameyossnowy"
version = "1.5.12"

repositories {
    mavenCentral()
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "me.champeau.jmh")
    apply(plugin = "com.vanniktech.maven.publish")
    apply(plugin = "signing")

    group = rootProject.group
    version = rootProject.version

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
        withSourcesJar()
        //withJavadocJar()
    }

    repositories {
        mavenCentral()
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        failOnNoDiscoveredTests = false
        jvmArgs("--add-modules", "jdk.incubator.vector")
    }

    dependencies {
        // JMH for benchmarks
        testImplementation("org.openjdk.jmh:jmh-core:1.37")
        testAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")

        compileOnly("org.jetbrains:annotations:26.0.2")
    }

    // Maven publishing with full POM metadata
    configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        coordinates(
            group as String,
            "uniform-${project.name.lowercase().replace("_", "-")}",
            version as String
        )

        pom {
            name.set("Uniform - ${project.name}")
            description.set(
                when (project.name) {
                    "core" -> "High-performance core library for Uniform serialization"
                    "annotation-processor" -> "Annotation processor for Uniform to generate serializers"
                    "json" -> "JSON module for Uniform serialization"
                    else -> "Uniform module ${project.name}"
                }
            )
            inceptionYear.set("2026")
            url.set("https://github.com/FlameyosSnowy/Uniform")
            licenses {
                license {
                    name.set("MIT")
                    url.set("https://opensource.org/licenses/MIT")
                    distribution.set("https://mit-license.org/")
                }
            }
            developers {
                developer {
                    id.set("flameyosflow")
                    name.set("FlameyosFlow")
                    url.set("https://github.com/FlameyosSnowy/")
                }
            }
            scm {
                url.set("https://github.com/FlameyosSnowy/Uniform")
                connection.set("scm:git:git://github.com/FlameyosSnowy/Uniform.git")
                developerConnection.set("scm:git:ssh://git@github.com/FlameyosSnowy/Uniform.git")
            }
        }

        publishToMavenCentral()
        signAllPublications()
    }

    tasks.withType<GenerateModuleMetadata> {
        enabled = false
    }

    signing {
        useGpgCmd()
    }

    tasks.withType<Test>().configureEach {
        jvmArgs("--add-modules", "jdk.incubator.vector")
    }

    afterEvaluate {
        tasks.named<com.vanniktech.maven.publish.tasks.JavadocJar>("plainJavadocJar") {
            dependsOn(tasks.named("javadoc"))
            archiveClassifier.set("javadoc")
            from(tasks.named<Javadoc>("javadoc"))
        }

        // Ensure metadata generation depends on Javadoc
        tasks.named("generateMetadataFileForMavenPublication") {
            dependsOn(tasks.named("plainJavadocJar"))
        }

        // Make publish depend on the Javadoc artifact
        tasks.named("publish") {
            dependsOn(tasks.named("plainJavadocJar"))
        }
    }
}