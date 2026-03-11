import xyz.jpenilla.runpaper.task.RunServer
import java.net.URI

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
        archiveBaseName.set(rootProject.name)
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

// ┌─────────────────────────────────────────────────────────────────────────┐
// │                       真实 Paper 服务器集成测试                          │
// │  与以上插件配置完全独立：下方任务不参与正常 build/assemble/test 流程。     │
// │  用法：./gradlew :core:serverIntegrationTest                            │
// └─────────────────────────────────────────────────────────────────────────┘

val shadowJarProvider = tasks.shadowJar

tasks.register<RunServer>("serverIntegrationTest") {
    group = "verification"
    description = "在真实 Paper 1.21.1 服务器中运行集成测试（含玩家攻击模拟）"
    dependsOn(shadowJarProvider, ":server-test:jar")

    minecraftVersion("1.21.1")
    runDirectory.set(layout.projectDirectory.dir("run-test"))

    doFirst {
        val serverDir = runDirectory.get().asFile
        val pluginsDir = serverDir.resolve("plugins")
        pluginsDir.mkdirs()

        // 接受 Minecraft EULA（首次启动必须，后续目录保留则无需重写）
        serverDir.resolve("eula.txt").writeText("eula=true\n")

        // 1. WarriorView 主插件（shadow + 已由 paperweight 重映射）
        shadowJarProvider.get().archiveFile.get().asFile
            .copyTo(pluginsDir.resolve("WarriorView.jar"), overwrite = true)

        // 2. 集成测试伴随插件（server-test 模块）
        val serverTestLibs = project(":server-test").layout.buildDirectory
            .asFile.get().resolve("libs")
        val serverTestJar = serverTestLibs.listFiles()
            ?.firstOrNull { it.extension == "jar" && !it.name.endsWith("-sources.jar") }
            ?: throw GradleException(
                "server-test JAR 未找到（目录: $serverTestLibs），请确认 :server-test:jar 已运行"
            )
        serverTestJar.copyTo(pluginsDir.resolve("WarriorViewServerTest.jar"), overwrite = true)

        // 3. PacketEvents（WarriorView 的强制前置插件）
        // 使用 Modrinth 发布的完整 fat JAR（包含 API 类），不是 Maven 仓库的 api-only artifact
        val peVersion = "2.11.2"
        val peJarName = "packetevents-spigot-$peVersion.jar"
        val peCacheDir = gradle.gradleUserHomeDir.resolve("caches/packetevents-plugin")
        val peCached = peCacheDir.resolve(peJarName)
        if (!peCached.exists()) {
            logger.lifecycle("[serverIntegrationTest] 下载 PacketEvents $peVersion fat JAR ...")
            peCacheDir.mkdirs()
            val url = URI(
                "https://github.com/retrooper/packetevents/releases/download/v$peVersion/packetevents-spigot-$peVersion.jar"
            ).toURL()
            url.openStream().use { input: java.io.InputStream -> peCached.outputStream().use { out: java.io.OutputStream -> input.copyTo(out) } }
        }
        peCached.copyTo(pluginsDir.resolve("packetevents.jar"), overwrite = true)
    }

    finalizedBy("checkIntegrationTestResults")
}

tasks.register("checkIntegrationTestResults") {
    group = "verification"
    description = "读取集成测试服务器日志，判断测试是否通过"
    mustRunAfter("serverIntegrationTest")
    doLast {
        val logFile = file("run-test/logs/latest.log")
        if (!logFile.exists()) {
            throw GradleException(
                "[Integration Test] 服务器日志未找到（${logFile.absolutePath}），" +
                "服务器可能未能正常启动"
            )
        }
        val log = logFile.readText()
        when {
            "[INTEGRATION_TEST] ALL_PASS" in log -> {
                logger.lifecycle("[Integration Test] ✓ 全部通过")
            }
            "[INTEGRATION_TEST] FAIL" in log -> {
                val failMsg = log.lines()
                    .firstOrNull { "[INTEGRATION_TEST] FAIL" in it }
                    ?: "（原因未知）"
                throw GradleException("[Integration Test] 失败: $failMsg")
            }
            else -> {
                throw GradleException(
                    "[Integration Test] 日志中未找到测试结果标记，" +
                    "服务器可能因其他原因异常退出，请查看 run-test/logs/latest.log"
                )
            }
        }
    }
}
