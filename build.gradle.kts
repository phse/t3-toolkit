import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

// Rechnerspezifische Einstellungen (nicht eingecheckt), z. B. platformLocalPath=/Applications/PhpStorm.app
val localProperties = Properties().apply {
    file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}
val platformLocalPath: String? = localProperties.getProperty("platformLocalPath")?.takeIf { file(it).exists() }

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
}

// ./gradlew runIde -Pscreenshots=<ordner> baut den Screenshot-Helfer mit ein (nie für Releases verwenden).
val screenshotDir = providers.gradleProperty("screenshots").orNull

sourceSets {
    main {
        if (screenshotDir != null) kotlin.srcDir("src/screenshot/kotlin")
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")

    intellijPlatform {
        if (platformLocalPath != null) {
            local(platformLocalPath)
        } else {
            create(IntelliJPlatformType.PhpStorm, providers.gradleProperty("platformVersion").get())
        }
        bundledPlugin("com.jetbrains.php")
        bundledPlugin("org.jetbrains.plugins.yaml")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        changeNotes = """
            <h3>1.1.0</h3>
            <ul>
              <li>Fluid Components: completion of component tags (prefix while typing, then all components of the
                  namespace, required parameters inserted).</li>
              <li>Fluid Components: gutter icon and Navigate | Related Symbol to the component files (fixture, styles,
                  scripts …) and back.</li>
              <li>New | TYPO3 | Fluid Component with a parameter table (name, type, optional) and fixture.</li>
              <li>Partials, layouts and components that cannot be found are marked as errors.</li>
              <li>New | TYPO3 | Middleware, registered in <code>Configuration/RequestMiddlewares.php</code>.</li>
              <li>New | TYPO3 | Event Listener: completion for the event class.</li>
              <li>HTML: no more "Unresolved file reference" for Fluid expressions like <code>href="{f:uri.typolink(…)}"</code>.</li>
              <li>New | TYPO3: existing files are reported in the dialog instead of failing.</li>
            </ul>
            <h3>1.0.0</h3>
            <ul>
              <li>First release.</li>
              <li>Fluid: navigation to partials, layouts and sections (TypoScript root paths and constants included),
                  usages of partials, layouts, templates and components in the gutter.</li>
              <li>Fluid Components (fluid_components): navigation to components and their parameters, parameter
                  completion, warning for missing required parameters.</li>
              <li>ViewHelpers: navigation to the class and to <code>registerArgument()</code>; global namespaces detected
                  from ext_localconf.php and configurable in Settings | Languages &amp; Frameworks | T3 Toolkit.</li>
              <li>Variables: navigation to <code>f:for</code>/<code>f:variable</code>, <code>&lt;fc:param&gt;</code>,
                  <code>f:render</code> arguments, TypoScript and PHP (<code>assign()</code>, <code>${"$"}processedData</code>).</li>
              <li>Services.yaml: navigation in both directions, quick-fix to move tags to PHP attributes, New | TYPO3 for
                  listeners, commands, message handlers, backend controllers and services.</li>
            </ul>
        """.trimIndent()
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
            .orElse(providers.gradleProperty("jetbrainsMarketplaceToken"))
    }

    pluginVerification {
        ides {
            platformLocalPath?.let { local(it) }
            create(IntelliJPlatformType.PhpStorm, providers.gradleProperty("verifyOldestVersion").get())
            create(IntelliJPlatformType.PhpStorm, providers.gradleProperty("verifyNewestVersion").get())
        }
    }
}

// Apache 2.0 verlangt, dass LICENSE und NOTICE mit dem Plugin verteilt werden.
tasks.jar {
    from(files("LICENSE", "NOTICE")) { into("META-INF") }
}

// Für Screenshots wird das Demo-Projekt an einen neutralen Ort kopiert, damit kein lokaler Benutzerpfad im Bild erscheint.
val screenshotProjectDir = "/Users/Shared/t3-demo"

val prepareScreenshotProject = tasks.register<Sync>("prepareScreenshotProject") {
    from("marketplace/demo-project")
    into(screenshotProjectDir)
}

tasks.runIde {
    jvmArgs("-Didea.trust.all.projects=true")
    if (screenshotDir != null) {
        dependsOn(prepareScreenshotProject)
        args(screenshotProjectDir)
        jvmArgs("-Dt3toolkit.screenshots=${file(screenshotDir).absolutePath}")
    } else {
        // ./gradlew runIde -PopenProject=/pfad/zum/projekt öffnet direkt ein Projekt in der Sandbox.
        providers.gradleProperty("openProject").orNull?.let { args(it) }
    }
}

tasks.withType<Zip>().configureEach {
    includeEmptyDirs = false
}
