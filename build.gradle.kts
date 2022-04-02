plugins {
    java
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("io.freefair.lombok") version "6.4.1"
}

group = "me.masmc05"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.dv8tion:JDA:5.0.0-alpha.9") {
        exclude(module = "opus-java")
    }
    implementation("com.j256.ormlite:ormlite-jdbc:6.1")
    implementation("org.xerial:sqlite-jdbc:3.36.0.3")
    implementation(group = "ch.qos.logback", name = "logback-classic", version = "1.2.10")
    implementation(group = "com.github.twitch4j", name = "twitch4j", version=  "1.9.0")
    implementation("com.electronwill.night-config:toml:3.6.5")
}

tasks.shadowJar {
    minimize()
}
tasks.jar {
    manifest {
        attributes["Main-Class"] = "me.masmc05.discordBotRoles.Main"
    }
}