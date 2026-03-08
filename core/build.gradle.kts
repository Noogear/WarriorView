plugins {
    id("io.papermc.paperweight.userdev")
    id("xyz.jpenilla.run-paper")
    id("com.gradleup.shadow")
}

repositories {
    maven("https://redempt.dev") // Crunch benchmark
}

dependencies {
    implementation(project(":api"))
    paperweight.paperDevBundle("1.21.1-R0.1-SNAPSHOT")

    // GloomLib（纯库，须 shade 进 JAR）
    val gloomlibVersion: String by project
    implementation("gloomlib:configuration:$gloomlibVersion")
    implementation("gloomlib:script:$gloomlibVersion")
    implementation("gloomlib:math:$gloomlibVersion")

    // PacketEvents（独立插件，运行时由服务端加载，不 shade）
    compileOnly("com.github.retrooper:packetevents-spigot:2.11.2")

    // 使得测试可以使用被 PaperAPI 打包进来的依赖
    testImplementation("com.google.guava:guava:33.2.1-jre")
    testImplementation("org.yaml:snakeyaml:2.2")
    testImplementation("com.github.retrooper:packetevents-spigot:2.11.2")

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

        // 重定位 GloomLib，避免与其他使用 GloomLib 的插件冲突
        relocate("gloomlib", "cn.warriorview.libs.gloomlib")
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
