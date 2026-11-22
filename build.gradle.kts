plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "cc.shockuletz"
version = "1.11.4"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    compileOnly("me.clip:placeholderapi:2.12.2")

    implementation("com.zaxxer:HikariCP:6.3.3") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("com.mysql:mysql-connector-j:9.7.0") {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("org.bstats:bstats-bukkit:3.1.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    shadowJar {
        archiveClassifier.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        relocate("org.bstats", "cc.shockuletz.shelo.libs.bstats")
        exclude(
            "module-info.class",
            "META-INF/versions/*/module-info.class",
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA"
        )
    }

    build {
        dependsOn(shadowJar)
    }
}
