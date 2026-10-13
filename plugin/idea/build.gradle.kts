import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.github.kusa233"
version = "1.0.0"

/**
 * The IntelliJ Platform the plugin is compiled, tested and verified against.
 *
 * Pinned to a concrete release rather than to `latest.release`, or to whichever IDE happens to be
 * installed on this machine. Three things have to agree: the API the plugin compiles against, the
 * `sinceBuild` it advertises, and the IDE its tests run inside. A floating version would let them
 * drift apart with nothing failing, and a local installation would make the build depend on one
 * machine's setup. The artifact is resolved from JetBrains' Maven repository, so any machine with
 * network access -- a colleague's laptop, a CI runner -- builds the same plugin.
 *
 * 2026.1.4 is deliberate rather than "the newest". `sinceBuild` is 261, so the plugin claims to
 * support the whole 2026.1 line; building and testing against the last patch release of that line
 * is what makes the claim mean something.
 */
val platformVersion = "2026.1.4"

repositories {
    mavenCentral()
    intellijPlatform {
        // Includes the IntelliJ Platform repository the IDE itself is published to, alongside the
        // plugin repository and Maven Central. Listed explicitly rather than relying on defaults so
        // that a build failure points here rather than at a missing repository somewhere else.
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IntelliJ IDEA, downloaded from JetBrains' repository at the version above.
        //
        // This is the *unified* IntelliJ IDEA, not Community Edition: since 2025.3 JetBrains merged
        // Community and Ultimate into one product, so `ideaIC` is no longer published for 2026.1 and
        // `intellijIdeaCommunity()` fails to resolve. `intellijIdea()` is that merged product, and
        // at this version it is the only IntelliJ IDEA that exists.
        //
        // What that costs is a larger download (about 1.6 GB rather than Community's 620 MB), paid
        // once and cached. What it preserves is the point of building against the platform at all:
        // the plugin is compiled, tested and verified against the release it claims to support,
        // rather than against whichever IDE happens to be installed on this machine.
        intellijIdea(platformVersion)

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
    // The plugin is called NekoJ, but its Gradle module is called `idea` because it lives at
    // `plugin/idea`. The platform plugin defaults both the sandbox plugin directory and the JAR
    // inside it to `project.name`, so without this the installed plugin would be a folder named
    // `idea/` containing `idea-1.0.0.jar` -- an internal build detail leaking into something users
    // see in their IDE's plugin list. This is the documented way to name it after the plugin.
    projectName = "NekoJ"

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
            // The same platform the plugin is compiled and tested against, downloaded rather than
            // taken from an installation. Verifying against the build's own platform is what makes
            // a failure here meaningful: an incompatible API call shows up as a real error instead
            // of as a mismatch between two different IDEs.
            create(IntelliJPlatformType.IntellijIdea, platformVersion)
        }
    }
}

// --------------------------------------------------------------------------------------- tests
//
// No separate "test IDE" task is declared. `:plugin:idea:test` already runs the suite inside a real
// IDE instance of the platform above -- the platform plugin prepares that sandbox itself -- so a
// second task pinned to the same IDE would run the same tests twice without observing anything the
// first run did not. Verifying against *other* IDE versions is a different job, and
// `pluginVerification.ides` above is where it belongs.

// The platform's test fixtures are JUnit 3/4-style (`BasePlatformTestCase` extends
// `junit.framework.TestCase`), so the JUnit 4 runner drives them; the JUnit Platform would need the
// vintage engine and would otherwise discover no tests at all.
tasks.test {
    useJUnit()
}

// ---------------------------------------------------------------------------------- packaging
//
// Both artifacts are named after the plugin rather than after the Gradle module. The module is
// called `idea` because it sits at `plugin/idea`, but nobody installing a plugin should have to know
// that: `NekoJ-1.0.0.zip` is what the IDE's "Install Plugin from Disk" dialog is handed, and
// `NekoJ.jar` is what shows up in the IDE's plugin directory.
//
// These are the *distributed* names, and they are not the same set of tasks: `buildPlugin` archives
// the output of `prepareSandbox`, whose plugin directory `intellijPlatform.projectName` already
// names, and the JAR it copies in is `composedJar` -- the base `jar` output plus the instrumented
// classes. Renaming only `jar` would rename an intermediate file and leave the shipped JAR as
// `idea-1.0.0.jar`, which is exactly the confusion these lines exist to avoid.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.ComposedJarTask>("composedJar") {
    archiveBaseName.set("NekoJ")
}

tasks.named<Zip>("buildPlugin") {
    archiveFileName.set("NekoJ-${project.version}.zip")
}
