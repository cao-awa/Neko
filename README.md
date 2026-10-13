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

The first build downloads IntelliJ IDEA 2026.1.4 from JetBrains' Maven repository (roughly 1.6 GB,
cached afterwards) and compiles the plugin against it. It is pinned to an exact release rather than
to the newest available, because three things have to agree: the API the plugin compiles against,
the `sinceBuild` it advertises, and the IDE its tests run inside. A floating version would let them
drift apart with nothing failing. Since `sinceBuild` is `261`, building and testing against the last
patch release of the 2026.1 line is what makes that claim mean something.

Because the platform comes from the repository rather than from an installation, the build needs
network access on its first run, and it no longer depends on any particular IDE being installed on
the machine — a CI runner builds the same plugin.

Note that this is the **unified** IntelliJ IDEA, not Community Edition. Since 2025.3 JetBrains
merged Community and Ultimate into a single product, so `ideaIC` is no longer published for 2026.1
and the plugin's `intellijIdeaCommunity()` helper does not resolve; `intellijIdea()` is that merged
product, and at this version it is the only IntelliJ IDEA there is.

One consequence is worth stating plainly, because it is a real loss: compiling against the merged
product no longer *proves* the plugin avoids paid-only APIs, the way building against Community did.
What keeps the plugin honest instead is `depends` in `plugin.xml`, which names exactly two modules —
`com.intellij.modules.platform` and `com.intellij.modules.lang`. An IDE missing either will refuse
to load the plugin rather than fail in some subtler way at run time.

Both modules target Java 21, because that is what the 2026.1 platform requires.

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

Static members:
```neko
class Neko() {
  static {
    private val counter: int = 0

    fun testStatic() {
      counter = counter + 1
    }
  }
}
```

A `static { ... }` block holds fields and functions alike, and every member inside it is static —
that is what the block says, so writing `static` again on a member is an error:

```neko
class Neko() {
  static fun testStatic() { }   // Error: a static member belongs in a 'static { ... }' block
}
```

Visibility is written on the member (`private val`, `public fun`), not on the block. A block cannot
nest, and cannot hold a `constructor`: a constructor builds an instance, so it is never static.
Members declared outside the block are instance members.

### Value class (`meow`)

`meow` before `class` declares the fields in the header and derives `equals` and `hashCode` from them:

```neko
meow class Point(val x: int, private val y: int) {
}
```

- The header list is the field list. Each entry is `[private|protected|public] val|var name: Type`,
  comma-separated, and there may be none: `meow class Unit()`.
- Two instances are equal when they are the same class and every field is equal. **Private fields
  take part**, as they do in a Kotlin data class.
- `==` and `!=` between two meow values use the derived `equals`. So do `a.equals(b)` and
  `a.hashCode()`: both are ordinary methods you can call.
- Writing your own `equals` or `hashCode` replaces the derived one.

The header is desugared into what the language already had — a field, a constructor parameter and a
`this.x = x` — so only the two derived methods are new. They are emitted as bytecode, because Neko
has no `is` operator and no cast, so neither could be written in Neko.

`meow` cannot be applied to an `interface` (an interface has no fields to derive from), and a meow
class must write its field list.

### Local variables

A local's type is inferred from its initializer when you leave it out:

```neko
instance = Instance()   // instance is an Instance
count = 1               // count is an int
```

Writing the type is still allowed and still checked. A bare `name = value` is a *declaration* when
nothing is in scope under that name, and an *assignment* when something is — so `str = "new value"`
still writes to the `str` declared above it, exactly as `Sample.neko` shows. The two are told apart
by the scope alone, which is why the compiler decides it rather than the parser: a bare name may
also be a field declared further down the same class.

The inferred local is mutable, and its name is registered only *after* its value is analysed, so
`count = count + 1` with no `count` in scope reports the reference rather than quietly declaring a
variable that starts from itself.

### The entry point

```neko
class Main {
  static {
    fun main() {
      println("no arguments needed")
    }
  }
}
```

A `static` `main` may take `string[]` or nothing at all. The JVM looks for exactly
`main(String[])`, so when the argument list is omitted the compiler emits a bridge that calls the
declared function.

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
