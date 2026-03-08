plugins {
    id("io.papermc.paperweight.userdev")
    id("xyz.jpenilla.run-paper")
    id("io.github.goooler.shadow")
}

repositories {
    maven("https://redempt.dev") // Crunch benchmark
}

dependencies {
    implementation(project(":api"))
    paperweight.paperDevBundle("1.21.1-R0.1-SNAPSHOT")

    // GloomLib
    implementation("gloomlib:configuration:1.2.2.0")
    implementation("gloomlib:script:1.1.0.0")
    implementation("gloomlib:math:1.1.0.0")
    implementation("com.github.retrooper:packetevents-spigot:2.11.2")
    
    // 使得测试可以使用被 PaperAPI 打包进来的依赖
    testImplementation("com.google.guava:guava:33.2.1-jre")
    testImplementation("org.yaml:snakeyaml:2.2")

    // JUnit 5
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")

    // Crunch（基准测试对比用）
    testImplementation("com.github.Redempt:Crunch:2.0.3")
}

tasks {
    test {
        useJUnitPlatform()
    }

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

    register<JavaExec>("runBenchmark") {
        description = "Run MathEngine vs Crunch benchmark"
        mainClass.set("cn.warriorview.script.math.MathBenchmark")
        classpath = sourceSets["test"].runtimeClasspath
    }

    // run-paper 开发服务器配置
    runServer {
        minecraftVersion("1.21.1")
    }
}
