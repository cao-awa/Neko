plugins {
    kotlin("jvm") version "2.4.0"
}

group = "com.github.kusa233"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.9")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Where the compiler writes the `.class` files.
 *
 * The leading `/` intentionally means "root of the current drive" (so on Windows this is
 * `D:\apsars-compile\classes` when the project lives on `D:`), matching the layout requested
 * for the sample build.
 */
val apsarsOutputDirectory = "/apsars-compile/classes"
val apsarsSample = "samples/com/github/kusa233/apsars/sample/Sample.aps"

/** Compiles [apsarsSample] into [apsarsOutputDirectory]. */
tasks.register<JavaExec>("compileApsars") {
    group = "apsars"
    description = "Compiles $apsarsSample into $apsarsOutputDirectory"
    dependsOn(tasks.named("classes"))
    mainClass.set("com.github.kusa233.apsars.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(apsarsSample, "--output", apsarsOutputDirectory)
}

/** Runs the class produced by [compileApsars]. */
tasks.register<JavaExec>("runApsarsSample") {
    group = "apsars"
    description = "Runs the compiled Sample class"
    dependsOn(tasks.named("compileApsars"))
    mainClass.set("com.github.kusa233.sample.Sample")
    classpath = files(apsarsOutputDirectory)
}
