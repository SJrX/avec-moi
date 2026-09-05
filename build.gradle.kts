import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter.ofPattern

fun properties(key: String) = project.findProperty(key).toString()

val JavaVersion = 21

fun getVersionNumber(): String {
  val major = properties("pluginMajorVersion")
  val minor = LocalDate.now().format(ofPattern("yyMMdd"))
  val build = System.getenv("BUILD_NUMBER") ?: 1

  val branchName = System.getenv("BRANCH_NAME") ?: "undefined"

  return if (branchName.matches(Regex("""[0-9]{3}\.x"""))) {
    "${major}.${minor}.${build}"
  } else {
    "${major}.${minor}.${build}-$branchName"
  }
}

plugins {
  id("java")
  id("org.jetbrains.intellij.platform") version "2.18.1"
  id("org.jetbrains.kotlin.jvm") version "2.4.10"
  id("idea")
}

group = "net.sjrx.intellij.plugins"
version = getVersionNumber()

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(JavaVersion))
  }
}

kotlin {
  jvmToolchain {
    languageVersion.set(JavaLanguageVersion.of(JavaVersion))
  }

  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_21)
  }
}

repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  // JSON for the hand-rolled MCP server. compileOnly: the IDE bundles kotlinx-serialization at
  // runtime (bundling our own would collide in the plugin classloader). DOM API only, so no
  // kotlinx-serialization Gradle plugin needed.
  compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.opentest4j:opentest4j:1.3.0")

  intellijPlatform {
    // Since 2025.3 (253) the separate IC/IU distributions were merged into a single
    // "IntelliJ IDEA" download, so create("IC", version) no longer resolves. The plugin
    // now exposes a dedicated accessor for it.
    intellijIdea(providers.gradleProperty("intellijVersion").get())

    // The terminal-nudge seam ("Step Done" button) injects text into the running
    // claude session via the bundled Terminal plugin's engine-agnostic bridge.
    bundledPlugin("org.jetbrains.plugins.terminal")

    pluginVerifier()
    testFramework(TestFrameworkType.Platform)
  }
}

// Forward-compat: launch a 2026.2 IDE WITHOUT changing the compile SDK or the min build.
// The plugin is still compiled against 2026.1 (sinceBuild 261); this only affects what `runIde262`
// launches. Useful because the reworked-terminal API we depend on is @ApiStatus.Experimental and
// can drift between releases. Run with: ./gradlew runIde262
intellijPlatformTesting {
  runIde {
    register("runIde262") {
      type.set(IntelliJPlatformType.IntellijIdea)
      version.set("2026.2")
    }
  }
}

val relativePath = "CHANGELOG"
val filePath = Paths.get(project.layout.buildDirectory.get().asFile.path, relativePath)

// Check if the file exists and read its content or use a default string
val changeLogContents: String = if (Files.exists(filePath)) {
  filePath.toFile().readText()
} else {
  "Development Build"
}

tasks {
  patchPluginXml {
    changeNotes.set(changeLogContents)
    sinceBuild.set(properties("sinceVersion"))
  }
}

idea {
  module {
    setDownloadJavadoc(true)
    setDownloadSources(true)
  }
}

// Searchable-options indexing adds a slow headless-IDE pass we don't need for a plugin
// without a Settings UI yet. Re-enable once there are settings to index.
tasks {
  buildSearchableOptions {
    enabled = false
  }
}

tasks.register<org.jetbrains.intellij.platform.gradle.tasks.PublishPluginTask>("publishPluginStandalone") {
  token.set(System.getenv("PUBLISH_TOKEN"))
  // pluginVersion is based on SemVer (https://semver.org) and supports pre-release labels.
  channels.set(listOf(System.getenv("RELEASE_CHANNEL") ?: "dev"))
  host.set("https://plugins.jetbrains.com")
  archiveFile.set(project.file("build/distributions/${project.name}-${project.version}.zip"))
}
