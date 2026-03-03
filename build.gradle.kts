plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "1.7.2" apply false
    id("xyz.jpenilla.run-paper") version "2.3.0" apply false
    id("io.github.goooler.shadow") version "8.1.8" apply false
}

subprojects {
    apply(plugin = "java")

    group = "cn.warriorview"
    version = "1.1.1"

    repositories {
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.io/repository/maven-releases/")
        maven("https://jitpack.io")
    }

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<Javadoc> {
        options.encoding = "UTF-8"
    }
}
