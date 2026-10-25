plugins {
    kotlin("jvm") version "2.4.0"
}

group = "com.github.kusa233"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // The root `gradle.properties` turns off the Kotlin plugin's automatic standard-library
    // dependency, because `plugin/idea` must not receive one: it runs inside the IDE, which already
    // ships a Kotlin standard library. This module, by contrast, runs on a plain JVM — `neko`'
    // command line and its tests — so it has to ask for the standard library itself.
    implementation(kotlin("stdlib"))

    implementation("org.ow2.asm:asm:9.9")

    testImplementation(kotlin("test"))
}

kotlin {
    // Java 21, not the newest JDK, because this module is a *library* as well as a command-line
    // compiler: `plugin/idea` links against it, and the IntelliJ Platform loads plugins on Java 21.
    // Gradle enforces that across a project dependency -- it refuses to give a Java 21 consumer a
    // Java 25 producer -- so the two modules have to agree on a target, and 21 is the one both can
    // meet. Neko gives nothing up by it: the class files it *emits* are Java 8 regardless (see
    // `BytecodeGenerator`), and everything it uses is long predates 21.
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// --------------------------------------------------------------------------------- configuration
//
// Every Neko path lives in the *root* `gradle.properties` rather than here, so adding a source file
// or moving the output is a configuration change and not a build-script change. Nothing below names
// an individual `.neko` file: the compiler is handed *directories* and compiles everything it finds.
//
// The paths are relative to the *root* project, not to this module, because `samples/` stays there.
// The samples are the specification the compiler is written against rather than an asset of the
// compiler, so a reader looking for them should not have to know which subdirectory the compiler
// happens to live in.

/** The root project directory, against which every configured path is resolved. */
val rootDirectory = rootProject.layout.projectDirectory

/** Reads a comma-separated path list from `gradle.properties`, trimmed and without blanks. */
fun nekoPaths(property: String): List<String> =
    (findProperty(property) as String? ?: "")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/** The directories holding the programs to compile. */
val nekoSourceDirectories: List<String> = nekoPaths("neko.sourceDirectories")

/**
 * Where the compiler writes the `.class` files, relative to the root project directory.
 *
 * The configured value is relative, and that is the single source of truth for the layout. The tasks
 * below are handed the *absolute* location — resolved against the root directory — because a Gradle
 * daemon's working directory is not guaranteed to be any particular directory: if the compiler
 * resolved a relative output path against its own directory while the run task resolved it against
 * another, the two would disagree about where the classes are.
 */
val nekoOutputDirectory: String = findProperty("neko.outputDirectory") as String
val nekoOutputPath = rootDirectory.dir(nekoOutputDirectory).asFile

/** The class `runNeko` executes when no program is named. */
val nekoMainClass: String = findProperty("neko.mainClass") as String

/**
 * Resolves configured directories to absolute paths, keeping only the ones that exist.
 *
 * A missing directory is tolerated at configuration time so the `doFirst` check can report *which*
 * ones are missing, rather than Gradle failing with a less specific message.
 */
fun existingDirectories(directories: List<String>): List<String> = directories
    .map { rootDirectory.dir(it).asFile }
    .filter { it.isDirectory }
    .map { it.absolutePath }

/** The programs, as the absolute paths of the directories that exist. */
val nekoSourcePaths: List<String> = existingDirectories(nekoSourceDirectories)

/** The name each runnable program is known by, paired with its fully-qualified class. */
val nekoPrograms: Map<String, String> = mapOf(
    "Sample" to "com.github.kusa233.neko.sample.Sample",
    "ExceptionSample" to "com.github.kusa233.neko.sample.ExceptionSample",
    "NullableSample" to "com.github.kusa233.neko.sample.NullableSample",
    "NyaSample" to "com.github.kusa233.neko.sample.NyaSample",
    "EnumSample" to "com.github.kusa233.neko.sample.EnumSample",
    "PurrSample" to "com.github.kusa233.neko.sample.PurrSample",
    "ForSample" to "com.github.kusa233.neko.sample.ForSample",
    "EffectRecovery" to "com.github.kusa233.neko.sample.EffectRecovery",
)

/**
 * Compiles every configured source directory into [nekoOutputPath].
 *
 * Compilation is per *program*: a class is resolved against the ones compiled earlier in the same
 * run, so all the sources are handed to the compiler together rather than file by file.
 */
tasks.register<JavaExec>("compileNeko") {
    group = "neko"
    description = "Compiles ${nekoSourceDirectories.joinToString(", ")} into $nekoOutputDirectory"
    dependsOn(tasks.named("classes"))
    mainClass.set("com.github.kusa233.neko.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    // The absolute output path is passed, and `workingDir` is pinned to the root as well, so a
    // relative `--output` would mean the same thing here as it does on the command line.
    workingDir = rootDirectory.asFile
    args = nekoSourcePaths + listOf("--output", nekoOutputPath.absolutePath)
    // Gradle cannot see the `.neko` files through the task's command line, so it cannot tell whether
    // they changed; the task always runs rather than being skipped as up to date.
    outputs.upToDateWhen { false }
    doFirst {
        check(nekoSourcePaths.isNotEmpty()) {
            "none of the directories in neko.sourceDirectories exist: " +
                nekoSourceDirectories.joinToString(", ")
        }
    }
}

/**
 * Runs one compiled program.
 *
 * Named with `gradlew :neko:runNeko -Pneko.program=ExceptionSample`. Adding a program is therefore a
 * configuration change rather than a new task.
 */
tasks.register<JavaExec>("runNeko") {
    group = "neko"
    description = "Runs a compiled Neko program, chosen with -Pneko.program"
    dependsOn(tasks.named("compileNeko"))
    val requested = (findProperty("neko.program") as String?)
        ?: nekoMainClass.substringAfterLast('.')
    mainClass.set(nekoPrograms[requested] ?: nekoMainClass)
    // The compiled program, plus the compiler's own classes: `EmitYieldException` is a Java class that
    // ships with the compiler rather than being generated from Neko, so a `yield` in a program resolves
    // to it at run time only if the main source set is on the classpath too.
    classpath = files(nekoOutputPath) + sourceSets["main"].runtimeClasspath
    workingDir = rootDirectory.asFile
}
