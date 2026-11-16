plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dec.alpha.ev56"
version = "1.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        clion("2025.1")
        pluginVerifier()
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "dec.alpha.ev56.visualizer"
        name = "Alpha 21164A Pipeline Visualizer"
        version = project.version.toString()
        description = "Visualizes DEC Alpha 21164A (EV56) quad-issue pipeline slot usage, MAF/WBUF stalls, and I-Cache fetches for GNU GAS assembly."

        ideaVersion {
            sinceBuild = "241"
            untilBuild = provider { null }
        }
    }

    buildSearchableOptions = false
}
