plugins {
    id("io.papermc.paperweight.userdev")
    id("xyz.jpenilla.run-paper")
    id("io.github.goooler.shadow")
}

dependencies {
    implementation(project(":api"))
    paperweight.paperDevBundle("1.21.1-R0.1-SNAPSHOT")
    implementation("com.github.retrooper:packetevents-spigot:2.11.2")
}

tasks {
    assemble {
        dependsOn(shadowJar)
    }

    shadowJar {
        archiveClassifier.set("")

        // 重定位 packetevents，避免与其他插件冲突
        // 注意：packetevents 作为独立插件加载时不需要重定位
        // 仅在 shade 进 JAR 时才需要
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    register<JavaExec>("runCustomTests") {
        mainClass.set("cn.warriorview.script.core.CompilationTypeValidationTest")
        classpath = sourceSets["test"].runtimeClasspath
    }

    // run-paper 开发服务器配置
    runServer {
        minecraftVersion("1.21.1")
    }
}
