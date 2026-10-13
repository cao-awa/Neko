import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.github.kusa233"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // The plugin is developed against the IDE that is actually installed, rather than against a
        // downloaded copy of the platform. Two things follow: no IDE is downloaded, so a build works
        // without network access to JetBrains' artifact repository; and the API the plugin compiles
        // against is exactly the API it will be loaded into, so a mistaken call fails here rather
        // than in a user's IDE.
        local("D:/IntelliJ IDEA 2026.1.4")

        // The platform test framework, so the plugin's own tests can drive a real PSI and editor.
        testFramework(TestFrameworkType.Platform)
    }

    // NekoJ is a *front end* for the compiler in `:neko`: it lexes and parses with the very same
    // classes the command line uses, so highlighting, completion and error reporting cannot drift
    // from what the compiler accepts. The parser, AST, symbol table and diagnostics are reused
    // rather than reimplemented, which is why this is a project dependency and not a copy.
    implementation(project(":neko"))

    // The IDE's own class path carries a Kotlin standard library (`util-8.jar`), so the plugin must
    // not package one: two copies would shadow each other and `verifyPluginProjectConfiguration`
    // rejects the duplicate. `compileOnly` is what states "this is present at run time, but it is
    // the host's job to provide it".
    compileOnly(kotlin("stdlib"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin {
    // The IntelliJ Platform for 2026.1 is built for Java 21, and `verifyPluginProjectConfiguration`
    // rejects a plugin whose Java target is above the platform's own -- a plugin compiled for Java
    // 25 could not be loaded by a 21 platform. So the *target* is 21, and `:neko` targets 21 as
    // well so the two agree: Gradle refuses a project dependency whose producer needs a newer JVM
    // than the consumer asks for.
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        name = "NekoJ"
        version = "1.0.0"

        description = """
            NekoJ brings the Neko language to IntelliJ IDEA: syntax highlighting, code completion
            and error reporting, all computed by the Neko compiler's own lexer, parser and analyzer.
        """.trimIndent()

        ideaVersion {
            // 261 is the 2026.1 platform. No `untilBuild` is declared: the plugin uses only stable
            // platform APIs, so it is better to keep loading into later builds than to have the IDE
            // refuse it on a version bump nobody has tested.
            sinceBuild = "261"
        }
    }

    // `verifyPluginProjectConfiguration` compares the build to the platform and to JetBrains'
    // recommendations; the checks it makes are the ones that catch a plugin that would load but
    // misbehave, so they are left on.
    pluginVerification {
        ides {
            local("D:/IntelliJ IDEA 2026.1.4")
        }
    }
}

// --------------------------------------------------------------------------------------- tests
//
// `testIde` is not registered by the platform plugin by default, so the IDE-driven test task has to
// be declared here: without it `gradlew testIde` camel-case-matches `testIdePerformance` and fails
// on the values that task needs. This one runs the module's tests against a real IDE instance, so a
// test observes the highlighting, completion and annotations a user of that IDE would see.
intellijPlatformTesting {
    testIde {
        register("testIdeProbe") {
            // The same IDE the plugin is compiled against. `localPath` is how the testing DSL names
            // it: unlike the `dependencies { intellijPlatform { ... } }` block, it has no `local()`
            // helper, and instead takes the installation directory directly.
            localPath.set(file("D:/IntelliJ IDEA 2026.1.4"))
        }
    }
}

// The platform's test fixtures are JUnit 3/4-style (`BasePlatformTestCase` extends
// `junit.framework.TestCase`), so the JUnit 4 runner drives them; the JUnit Platform would need the
// vintage engine and would otherwise discover no tests at all.
tasks.test {
    useJUnit()
}

// ---------------------------------------------------------------------------------- packaging
//
// Both artifacts are named after the plugin rather than after the Gradle module. The module is
// called `idea` because it sits at `plugin/idea`, but nobody installing a plugin should have to
// know that: `NekoJ-1.0.0.zip` is what the IDE's "Install Plugin from Disk" dialog is handed, and
// `NekoJ.jar` is what shows up in the IDE's plugin directory.
tasks.jar {
    archiveBaseName.set("NekoJ")
}

tasks.named<Zip>("buildPlugin") {
    archiveFileName.set("NekoJ-${project.version}.zip")
}
