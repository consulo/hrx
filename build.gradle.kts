plugins {
    application
    id("org.graalvm.buildtools.native") version "1.1.18"
}

group = "consulo"
// release version of hrx, bumped by the release workflow
version = file("version.txt").readText().trim()

val consuloVersion = "3-SNAPSHOT"
val picocliVersion = "4.7.7"

repositories {
    // consulo.http.client.* artifacts: run `mvn install` in consulo-http-client first
    mavenLocal()
    maven("https://maven.consulo.dev/repository/snapshots/")
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        // GraalVM/Mandrel: needed for nativeCompile and for the tracing agent (-Pagent)
        nativeImageCapable = true
    }
}

dependencies {
    // .http lexer/parser/PSI, request execution (consulo.httpClient.execution) and the Rhino script engine - same code as the IDE plugin
    implementation("consulo.plugin:consulo.http.client.api:$consuloVersion")
    implementation("consulo.plugin:consulo.http.client.script.rhino:$consuloVersion")

    // light (headless) Consulo application, same set as consulo.maven:bnf-artifact
    implementation("consulo:consulo-test-impl:$consuloVersion")
    implementation("consulo:consulo-application-api:$consuloVersion")
    implementation("consulo:consulo-component-impl:$consuloVersion")
    implementation("consulo:consulo-project-api:$consuloVersion")
    implementation("consulo:consulo-project-content-api:$consuloVersion")
    implementation("consulo:consulo-language-api:$consuloVersion")
    implementation("consulo:consulo-language-impl:$consuloVersion")
    implementation("consulo:consulo-disposer-impl:$consuloVersion")
    implementation("consulo:consulo-document-impl:$consuloVersion")
    implementation("consulo:consulo-platform-impl:$consuloVersion") {
        exclude(group = "net.java.dev.jna")
    }
    implementation("consulo:consulo-http-api:$consuloVersion")
    // platform http stack: java.net.http executor, remote platform senders
    implementation("consulo:consulo-http-impl:$consuloVersion")
    // --agent: requests sent by a Consulo remote agent (gRPC), RemoteAgentHttpRequestSender
    implementation("consulo:consulo-remote-server-agent-impl:$consuloVersion")
    implementation("consulo:consulo-util-nodep:$consuloVersion")
    implementation("consulo:consulo-util-lang:$consuloVersion")
    implementation("consulo:consulo-util-collection-via-trove:$consuloVersion")

    // provided by the IDE platform at runtime, needed explicitly here
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.apache.commons:commons-lang3:3.18.0")

    implementation("info.picocli:picocli:$picocliVersion")
    annotationProcessor("info.picocli:picocli-codegen:$picocliVersion")

    constraints {
        // gRPC brings guava -android, whose AbstractFuture uses sun.misc.Unsafe (JDK 24+ warns on every start);
        // guava -jre 33.8 uses VarHandle
        implementation("com.google.guava:guava:33.8.0-jre")
    }

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The same end-to-end tests (HttpCliTest) run in three ways:
//   gradle test              - in process
//   gradle distributionTest  - the installed start script (real class path order), part of `check`
//   gradle nativeCliTest     - the native binary; -PhttpCliExecutable=/path/to/http-cli tests a prebuilt one
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

val isWindows = System.getProperty("os.name").lowercase().contains("windows")

val distributionTest by tasks.registering(Test::class) {
    description = "Runs the CLI tests against the installed distribution"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    dependsOn(tasks.installDist)
    val executable = layout.buildDirectory.file("install/hrx/bin/hrx" + if (isWindows) ".bat" else "")
    systemProperty("httpCli.executable", executable.get().asFile.absolutePath)
}

val nativeCliTest by tasks.registering(Test::class) {
    description = "Runs the CLI tests against the native binary"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    val prebuilt = providers.gradleProperty("httpCliExecutable").orNull
    if (prebuilt == null) {
        dependsOn(tasks.named("nativeCompile"))
    }
    val executable = prebuilt ?: layout.buildDirectory.file("native/nativeCompile/hrx" + if (isWindows) ".exe" else "")
        .get().asFile.absolutePath
    systemProperty("httpCli.executable", executable)
}

tasks.check {
    dependsOn(distributionTest)
}

// version.txt -> HrxVersion.VERSION; a constant needs no resource or reflection metadata in the native image
val generateVersion by tasks.registering {
    val versionText = project.version.toString()
    val outputDir = layout.buildDirectory.dir("generated/sources/version")
    inputs.property("version", versionText)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("consulo/httpClient/cli/HrxVersion.java").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package consulo.httpClient.cli;
            |
            |/**
            | * Generated from version.txt.
            | */
            |final class HrxVersion {
            |    static final String VERSION = "$versionText";
            |
            |    private HrxVersion() {
            |    }
            |}
            |""".trimMargin()
        )
    }
}

sourceSets.main {
    java.srcDir(generateVersion)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Aproject=${project.group}/${project.name}")
}

// license and third-party notices: in the jar and in the root of the JVM distribution
tasks.jar {
    from(rootDir) {
        include("LICENSE.txt", "NOTICE.txt")
        into("META-INF")
    }
}

distributions {
    main {
        contents {
            from(rootDir) {
                include("LICENSE.txt", "NOTICE.txt")
            }
        }
    }
}

// cmd.exe limits a line to 8191 characters, too short for ~100 jar paths in hrx.bat:
// use a wildcard, with the hrx jar first (its META-INF/services must win, see HttpCliLocalizeManager)
tasks.startScripts {
    doLast {
        val jarName = tasks.jar.get().archiveFileName.get()
        windowsScript.writeText(
            windowsScript.readText().replace(
                Regex("(?m)^set CLASSPATH=.*$"),
                Regex.escapeReplacement("set CLASSPATH=%APP_HOME%\\lib\\$jarName;%APP_HOME%\\lib\\*")
            )
        )
    }
}

application {
    mainClass = "consulo.httpClient.cli.HttpCliMain"
    applicationName = "hrx"
}

graalvmNative {
    toolchainDetection = true
    binaries {
        named("main") {
            imageName = "hrx"
            mainClass = "consulo.httpClient.cli.HttpCliMain"
            buildArgs.addAll(
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
                // Consulo CharsetToolkit needs UTF-32 and others at class init
                "-H:+AddAllCharsets",
                // netty-transport registers java.lang.management for reflection, which links libmanagement_ext;
                // its other entries are in META-INF/native-image/consulo/http-cli/netty-transport
                "--exclude-config", ".*netty-transport-[^/]*\\.jar", "META-INF/native-image/io\\.netty/netty-transport/reflect-config\\.json",
                "--enable-url-protocols=http,https"
            )
        }
    }
    metadataRepository {
        enabled = true
        // the repository registers java.lang.management for netty, which links libmanagement_ext.so;
        // netty jars ship their own metadata
        excludedModules.addAll(
            "io.netty:netty-common",
            "io.netty:netty-buffer",
            "io.netty:netty-transport",
            "io.netty:netty-transport-native-unix-common",
            "io.netty:netty-resolver",
            "io.netty:netty-codec",
            "io.netty:netty-codec-http",
            "io.netty:netty-codec-http2",
            "io.netty:netty-codec-socks",
            "io.netty:netty-handler",
            "io.netty:netty-handler-proxy"
        )
    }
}

// `gradle -Pagent run --args="..."` collects reflection/resource metadata with the GraalVM tracing agent,
// `gradle metadataCopy` merges it into src/main/resources/META-INF/native-image
tasks.named<org.graalvm.buildtools.gradle.tasks.MetadataCopyTask>("metadataCopy") {
    inputTaskNames.add("run")
    outputDirectories.add("src/main/resources/META-INF/native-image/consulo/http-cli")
    mergeWithExisting = true
}

tasks.named<JavaExec>("run") {
    // a failing request must not hide the collected agent metadata
    isIgnoreExitValue = project.hasProperty("agent")
}

configurations.runtimeClasspath {
    // JNA only serves the desktop file system mediator (FileSystemMediatorOverride), not needed here
    exclude(group = "net.java.dev.jna")
    exclude(group = "consulo", module = "consulo-util-jna")
}
