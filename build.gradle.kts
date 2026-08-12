plugins {
    java
}

group = "dev.biomeislands"
version = "1.5.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Pin a stable 26.2 API. Using 26.2.build.+ can resolve an alpha/beta API
    // whose binary shape differs from the stable server (Registry changed during 26.2).
    compileOnly("io.papermc.paper:paper-api:26.2.build.84-stable")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
