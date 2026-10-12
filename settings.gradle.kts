plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Neko"

// The compiler, its standard library and its tests.
include(":neko")

// NekoJ: the IntelliJ IDEA plugin, which reuses `neko`'s lexer, parser, AST and analyzer.
include(":plugin:idea")
