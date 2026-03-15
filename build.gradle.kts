plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "1.7.2" apply false
    id("xyz.jpenilla.run-paper") version "2.3.0" apply false
    id("com.gradleup.shadow") version "9.0.0-beta12" apply false
}

subprojects {
    apply(plugin = "java")

    group = "cn.warriorview"
    version = "2.0.9"

    repositories {
        mavenLocal()
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
