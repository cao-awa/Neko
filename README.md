# Neko language

Neko is a toy JVM language for experimenting with compilers: it has classes, interfaces, `emit`/`raise`
error control, and it compiles straight to Java bytecode.

## Project layout

The build is a Gradle multi-module project. The compiler and the IDE plugin are separate modules so
that using one does not drag in the other: the plugin depends on the compiler, never the reverse.

| Path            | Module         | What it is                                                   |
|-----------------|----------------|--------------------------------------------------------------|
| `neko/`         | `:neko`        | The compiler: lexer, parser, AST, analyzer, bytecode emitter  |
| `plugin/idea/`  | `:plugin:idea` | NekoJ, the IntelliJ IDEA plugin, built on `:neko`             |
| `stdlib/`       | —              | The Neko standard library, written in Neko                    |
| `samples/`      | —              | Example programs                                              |
| `neko-compile/` | —              | Compiler output; not checked in                               |

`stdlib/` and `samples/` are source, not modules: they are read by the build and compiled by Neko
itself, so a new file there needs no build change.

## Building and running

Tasks are module-qualified now that there is more than one module.

```bash
# Compile every .neko file under the configured directories.
./gradlew :neko:compileNeko

# Run a compiled program. The name is looked up in the build, so adding a program to the
# list in neko/build.gradle.kts is a configuration change rather than a new task.
./gradlew :neko:runNeko -Pneko.program=ExceptionSample

# Tests.
./gradlew :neko:test                     # the compiler: 168 tests
./gradlew :plugin:idea:test              # the plugin: 17 tests, in a real IDE instance
```

Compiled classes are written to `neko-compile/classes`, relative to the project directory. The path
and the directories that are compiled come from `gradle.properties`:

```properties
neko.libraryDirectories=stdlib
neko.sourceDirectories=samples/com/github/kusa233/neko/sample
neko.outputDirectory=neko-compile/classes
```

Every directory listed is searched recursively, so adding a source file means adding it to a
directory. The standard library is listed separately from the programs because the two play
different roles, though both are compiled in the same run.

`ExceptionSample` prints:

```
123
456
1
```

## Standard library

The standard library is written in Neko, in `stdlib/`, and is **not** embedded in the compiler: a
program that uses `raise` or `try` is compiled against it, and if it cannot be found the compiler
reports that as an error rather than substituting a built-in. This keeps the language's error
mechanism honest — what the compiler lowers against is ordinary Neko source you can read.

There is exactly one built-in that is not written in Neko: `println`, in four overloads (`int`,
`string`, `bool`, `Object`). It mirrors `System.out.println` and is modelled as an ordinary function
so the analyzer and the emitter share one resolution path.

## NekoJ — the IntelliJ IDEA plugin

`plugin/idea` builds **NekoJ**, which gives `.neko` files syntax highlighting, code completion and
error reporting in IntelliJ IDEA.

Its defining decision is that it does not reimplement anything. The plugin depends on `:neko` and
runs the compiler's own lexer, parser and analyzer, so:

- errors in the editor are the compiler's errors, worded identically, and they improve whenever the
  compiler does;
- completion offers the symbols the analyzer actually resolved, so a name appears only once it
  exists;
- highlight colours come from the compiler's tokens, passed through unchanged.

There is no second grammar to drift out of step with the real one.

### Building and installing

```bash
./gradlew :plugin:idea:buildPlugin
```

The result is `plugin/idea/build/distributions/NekoJ-1.0.0.zip`. Install it with
**Settings → Plugins → ⚙ → Install Plugin from Disk…**, then restart the IDE.

The plugin is built against the IDE installed at `D:/IntelliJ IDEA 2026.1.4` rather than against a
downloaded copy of the platform, so no IDE is fetched during a build, and the API it compiles against
is exactly the API it loads into. Both modules target Java 21, because that is what the 2026.1
platform requires.

```bash
# Checks the plugin against a real IDE: extension points, API usage, compatibility.
./gradlew :plugin:idea:verifyPlugin
```

### How highlighting works

Two details are worth knowing, because both are non-obvious and both are load-bearing.

The compiler's lexer **discards** whitespace and comments — the parser has no use for either. An
editor cannot: comments must be colourable and foldable, and the platform requires consecutive tokens
to tile the buffer with no gaps. So `NekoHighlightingLexer` runs the compiler's lexer for its tokens
and then re-reads the holes as whitespace, `//` comments and `/* */` comments. Everything the
compiler produced passes through untouched.

`NekoParserDefinition` is **not optional**. The platform decides a file's language by looking for a
parser definition, and a language without one degrades silently to plain text: no highlighting, no
annotations, no completion, and no error anywhere to say so. It is also where the whitespace and
comment token sets are declared, which is what gives the editor real `PsiComment` and `PsiWhiteSpace`
leaves.

The syntax tree itself is deliberately flat. Writing the grammar a second time would mean a parser
free to disagree with the compiler's, and the structure the IDE reports on comes from the compiler
anyway, through the annotator.

## Syntax

### Class

```neko
class Neko() {
}
```

### Function (Method)

Member function:
```neko
class Neko() {
  fun test() {
  }
}
```

Static function:
```neko
class Neko() {
  static {
    fun testStatic() {
    }
  }
}
```

### Error control

Emit and raise:
```neko
class Neko() {
  fun invoker() {
    // Must use try to call a emitted function.
    try test() { emit1, emit2 ->
      println(emit1 * emit2)
    }
  }

  fun inheritEmit() {
    // When function signature has correct
    // emitted like calling function,
    // Then don't need to try.
    test()
  } emit [int, int]

  fun test() {
    raise 123, 456
  } emit [int, int]
}
```

Emit and raise with returning value:
```neko
class Neko() {
  fun invoker() {
    // Use a variable to receive it return value.
    result: int = try test(true) { emit1, emit2 ->
      println(emit1 * emit2)
      // If raised, return this.
      yield emit1 * emit2
    }
  }

  fun test(flag: bool): int {
    if (flag) {
      raise 123, 456
    } else {
      return 0
    }
  } emit [int, int]
}
```
