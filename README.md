# Neko language

Neko is a toy JVM language for experimenting with compilers: it has classes, interfaces,
`emit`/`scratch`/`nyan` error control, and it compiles straight to Java bytecode.

[中文](README_zh.md)

## Project layout

The build is a Gradle multi-module project. The compiler and the IDE plugin are separate modules so
that using one does not drag in the other: the plugin depends on the compiler, never the reverse.

| Path            | Module         | What it is                                                   |
|-----------------|----------------|--------------------------------------------------------------|
| `neko/`         | `:neko`        | The compiler: lexer, parser, AST, analyzer, bytecode emitter  |
| `plugin/idea/`  | `:plugin:idea` | NekoJ, the IntelliJ IDEA plugin, built on `:neko`             |
| `stdlib/`       | —            | The Neko standard library, compiled by the compiler itself     |
| `samples/`      | —            | Example programs                                              |
| `neko-compile/` | —            | Compiler output; not checked in                               |

`stdlib/` and `samples/` are source, not modules: both are compiled by Neko itself, so a new file
there needs no build change. `stdlib/` is *not* a source directory the build passes to the compiler —
the compiler prepends it to every compile from the copy packaged into its own jar. Listing it twice
would declare every library class twice.

## Building and running

Tasks are module-qualified now that there is more than one module.

```bash
# Compile every .neko file under the configured directories.
./gradlew :neko:compileNeko

# Run a compiled program. The name is looked up in the build, so adding a program to the
# list in neko/build.gradle.kts is a configuration change rather than a new task.
./gradlew :neko:runNeko -Pneko.program=ExceptionMapping

# Tests.
./gradlew :neko:test                     # the compiler: 579 tests
./gradlew :plugin:idea:test              # the plugin: 59 tests, in a real IDE instance

# The compiler as one self-contained jar.
./gradlew :neko:executableJar
java -jar neko/build/executable/neko-1.0-SNAPSHOT-all.jar samples --output out

# Compile and run one class in the same command.
java -jar neko/build/executable/neko-1.0-SNAPSHOT-all.jar samples \
    --run com.github.kusa233.neko.sample.effect.EffectRecovery
```

## The command line

`executableJar` writes `neko-1.0-SNAPSHOT-all.jar`, which runs on any JVM 21 or newer with nothing
else on the classpath:

```bash
java -jar neko-1.0-SNAPSHOT-all.jar <file-or-directory>... [options]
```

A `.neko` file or a directory of them; a directory is searched recursively. All the arguments are
compiled as **one program**, which is what lets a class in one file name a class in another.

```
Options:
  --output <directory>   Where to write the compiled class files. A relative path is
                         resolved against the current directory, so the classes can be
                         kept inside the project. When omitted, no class files are
                         written; the compilation is only reported, or run with --run.
  --run <class>          After compiling, run the static main(String[]) of <class>. The class
                         name is fully qualified and written in dot form, such as
                         neko.sample.TestClass. Its output goes straight to the terminal, and
                         the compilation summary is not printed.
  --stdlib-path <dir>    Compile against the standard library in <dir> instead of the one the
                         compiler carries. The directory is searched recursively for .neko files.
  --color, --no-color    Force or suppress coloured diagnostics.
  -h, --help             Show this message.
```

The exit status is `0` on success, `1` when the program failed to compile (every diagnostic is
printed, in source order), and `2` for an internal compiler error —which must never be mistaken for
a problem with the program being compiled.

### Running a class: `--run`

`--run <class>` compiles the program and then executes the named class' `main`:

```bash
java -jar neko-all.jar samples --run com.github.kusa233.neko.sample.effect.EffectRecovery
```

The name is the one the JVM sees: the package from the file's `locate` clause plus the class that
declares `main`, in dot form. A class with `locate neko.sample` and `class TestClass` is
`neko.sample.TestClass`. `main` may be written with or without a `string[]` parameter; the compiler
emits the `main(String[])` bridge the JVM looks for, so both reach the runner.

The run is fed the bytes this compilation produced rather than classes read back from `--output`, so
no output directory is needed and a stale class file cannot answer instead. With `--run` the
compilation summary is suppressed — the program's own output is the result — but diagnostics are
still printed first. The exit status is then `0` when `main` returns and `1` when the class is
missing, has no `static main(String[])`, or `main` throws; a thrown exception is printed with the
program's frames, without the reflection plumbing the runner used to reach it.

### Why the jar is a separate task

The module's ordinary `jar` stays a plain library jar. `:plugin:idea` depends on `:neko` and ships its
output inside the plugin, and the IDE already provides a Kotlin standard library —filling that jar
with `kotlin-stdlib` and ASM would put a second copy on the plugin's classpath, which is exactly the
bundled-runtime conflict `verifyPluginProjectConfiguration` rejects. So `executableJar` writes a
*second* jar, self-contained and named as such. A reader who wants the command line takes that file; a
plugin developer keeps linking against the ordinary one.

The dependencies are unpacked into the jar rather than referenced from a `Class-Path` manifest entry,
because the point is one file that can be copied to another machine and run. The `Main-Class` entry is
what makes `-jar` work at all: without it the JVM refuses to pick an entry point.

### Bringing your own standard library

`--stdlib-path` compiles against a library of your own instead of the packaged one:

```bash
java -jar neko-all.jar samples --output out --stdlib-path ./my-stdlib
```

It is for working on the library itself, or for pinning a version of it to a project. The library is
still compiled first and a program still never names it —the only thing that changes is where its
sources come from.

A supplied library **replaces** the packaged one rather than adding to it, and that is deliberate: a
reader who pinned an older library should not quietly also get the newer one's declarations. Two
consequences follow, and both are reported rather than papered over. A path that is not a directory,
or that holds no `.neko` file, is an error on the command line —falling back to the packaged library
would make a mistyped path look like a working build. And an empty library stays empty, so a program
using `scratch` fails with "the compiler's own standard library is missing", which is the truth.

Compiled classes are written to `neko-compile/classes`, relative to the project directory. The path
and the directories that are compiled come from `gradle.properties`:

```properties
neko.sourceDirectories=samples/com/github/kusa233/neko/sample
neko.outputDirectory=neko-compile/classes
```

Every directory listed is searched recursively, so adding a source file means adding it to a
directory.

The samples are organised one directory per topic, and **each directory is its own package**: a file
under `sample/lock/` declares `locate com.github.kusa233.neko.sample.lock`. That is what splits the
output — `neko-compile/classes/com/github/kusa233/neko/sample/lock/PurrSample.class` and so on —
because a class is written under the directory its `locate` names, exactly as a Java class file is.

`ExceptionMapping` is the `scratch`/`nyan`/`try` reference, and every line it prints is described by
the comment above it — the samples carry their own expected output, so read them rather than a table
here:

```bash
./gradlew :neko:runNeko -Pneko.program=ExceptionMapping
```

`NullableSample` exercises the nullable feature end to end, and its comments mark the lines that must
*not* compile as clearly as the ones that must —the errors are as much a part of the sample as the
output. It is the closest thing to a reference for [nullable types](#nullable-types):

```bash
./gradlew :neko:runNeko -Pneko.program=NullableSample
```

`NyaSample` does the same for [`nya`](#checking-what-the-program-believes), and `ExceptionMapping` for
`scratch`, `nyan` and `try`. `EffectRecovery` is the reference for the same mechanism —the one the
`emit`/`scratch`/`nyan` section below walks through. `EnumSample` shows [`miao class`](#enum-miao) —
the constants, the values they can carry, and `values()`/`valueOf()` —and `PurrSample` shows
[locking](#locking-purr-purred-purrcls-purredcls):

```bash
./gradlew :neko:runNeko -Pneko.program=NyaSample
./gradlew :neko:runNeko -Pneko.program=ExceptionMapping
./gradlew :neko:runNeko -Pneko.program=EffectRecovery
./gradlew :neko:runNeko -Pneko.program=EnumSample
./gradlew :neko:runNeko -Pneko.program=PurrSample
./gradlew :neko:runNeko -Pneko.program=InstanceSample
./gradlew :neko:runNeko -Pneko.program=ForSample
```

## Standard library

The library is written in Neko, in `stdlib/`, and **the compiler carries it**: every compilation
prepends it to whatever program it was asked to build. A program therefore never names it, and a
caller never passes it —`gradlew compileNeko`, the command line, the IDE and the tests all get it by
doing nothing.

Its sources are packaged into the compiler's own jar as resources rather than read from `stdlib/` at
run time. That is what makes a built jar usable from another machine, from the plugin (which receives
the compiler as a jar on its classpath) and from a test run anywhere —none of which can be assumed to
sit next to this repository. `StandardLibrary` reads them back by a path that resolves the same way
whether they came from a jar or from a directory of class files.

The library holds only what the language cannot borrow from the JVM:

* `EmitYieldException` —the carrier a `yield` throws. It is checked rather than unchecked on purpose:
  a `yield` is only legal inside a handler a `try` declared, so every method between them has to say
  it may pass one on. That is what makes the effect visible in the class file instead of a surprise at
  run time. Its one field is `public` —the code that reads it is the `try`'s generated handler, in a
  *different* class, so `private` would compile cleanly and fail with `IllegalAccessError` the first
  time a `yield` fired.

Everything else the effect mechanism needs is generated: a `try`'s `{ ... }` block becomes a class
that implements the `ScratchHandlerN` for its arity, and a `scratch` compiles to an
`invokeinterface` asking that object for an answer. The `ScratchHandler0` … `ScratchHandler7`
interfaces are library source too, one per arity.

There is exactly one built-in that is not Neko source: `println`, in one overload per built-in type —
the seven numeric ones, `string`, `bool` and `Object`. It mirrors `System.out.println` and is modelled
as an ordinary function so the analyzer and the emitter share one resolution path. `byte`, `short` and
`char` have no `PrintStream` method of their own, so those three are declared with the Neko type and
emitted as the `int` overload Java widens them to anyway.

The iteration protocol `for ... in` walks is **the JDK's own**, not the library's: `java.lang.Iterable`
and `java.util.Iterator`. A class becomes iterable by implementing the interface the JVM already has,
which is what makes a Neko object usable as a Java `Iterable` and lets a Neko `for` walk a
`java.util.List` handed over by the platform.

`Iterable` needs no import because it is in `java.lang`; `Iterator` is in `java.util`, so a file that
writes `fun iterator(): Iterator` says `import java.util`. That is the ordinary rule for a platform
name, not a special case for the loop.

`next()` returns `Object` because that is what the erased interface declares. Neko erases generics
without emitting the bridge methods a parameterised interface needs —`fun next(): int` would compile
to `()I` while `Iterator<T>` declares `()Ljava/lang/Object;`, and the two would never meet. So `Object`
is not a workaround against the JDK, it *is* the erased signature; `for` inserts the cast and the
unboxing, and the loop variable's declared type is still what the body reads.

## NekoJ —the IntelliJ IDEA plugin

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
**Settings → Plugins → ⚠ → Install Plugin from Disk**, then restart the IDE.

The first build downloads IntelliJ IDEA 2026.1.4 from JetBrains' Maven repository (roughly 1.6 GB,
cached afterwards) and compiles the plugin against it. It is pinned to an exact release rather than
to the newest available, because three things have to agree: the API the plugin compiles against,
the `sinceBuild` it advertises, and the IDE its tests run inside. A floating version would let them
drift apart with nothing failing. Since `sinceBuild` is `261`, building and testing against the last
patch release of the 2026.1 line is what makes that claim mean something.

Because the platform comes from the repository rather than from an installation, the build needs
network access on its first run, and it no longer depends on any particular IDE being installed on
the machine —a CI runner builds the same plugin.

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

### What completion offers

The list is the compiler's own symbol table, not a hand-written catalogue, so a name appears exactly
when it exists:

- **Locals in scope** —the variables declared above the caret in the enclosing blocks, and the
  parameters of the enclosing function, lambda or `try` extractor. A variable
  declared *below* the caret is not offered, because writing it there would not compile.
- **The enclosing class' own members** —the static ones in a static body, and the instance ones as
  well in an instance body. Members of other classes are not offered bare, because reaching one
  needs a receiver.
- **Classes** —the program's own, and the Java platform's. The platform's are read from the running
  JDK rather than listed, and only the ones this file can actually name are offered: `java.lang`,
  and the file's imported packages.
- **Types, in a type position** —after a `:`, inside an `emit [ ... ]` clause, and after its `->`. A
  type position gets its own list, since a variable is not a type.
- **The built-ins**, each shown with the Java type it is, so `Integer` and `int` are visibly the same
  type rather than two.

### Automatic imports

A class the file cannot name yet is offered too —with the `import` clause that would make it
nameable, written for you when you accept it. Type `Hash`, pick `HashMap` from the popup, and the
file gains `import java.util` as well as the name. The same holds for the program's own classes: a
class declared in another `locate` package is offered with its package shown, and pressing Enter
writes `import other.pkg.Helper` so that the name you accepted actually compiles.

This depends on three things agreeing, which is why it is built the way it is:

- **The index is the compiler's.** `PlatformLibrary` reads the package and class list out of the
  running JDK's `java.base` module, so a name is offered exactly when the analyzer would resolve it.
  No table is kept, because a JDK gains and loses classes between releases.
- **The fix is the compiler's.** The analyzer reports an unresolvable class as an error carrying
  `suggestedImport`, and the IDE turns that field into an Alt+Enter action. Re-deriving the clause in
  the plugin would be a second implementation free to disagree with the one that decides whether the
  file compiles.
- **The placement is the file's.** A Neko header is ordered —`locate`, then every `import`, then the
  classes —so `NekoImports` works out where the clause goes from the file's own text rather than
  from a line number, and refuses to write a clause the file already has (a second identical import
  is an error).

Classes needing an import are offered **only once a prefix has been typed**. `java.base` holds 3373
classes; listing them all on an empty prefix both overflows the popup and buries the handful of names
the reader was reaching for. `java.lang` and already-imported packages need no clause, so they are
offered regardless.

Note what the language itself requires: **the import is compulsory**. `HashMap` without
`import java.util` is an error, not a warning —the compiler cannot know a class from a package it was
never told to look in, and resolving it silently would make the name's package invisible in the source.
The IDE writes the clause; it does not stand in for one. The same rule applies to the program's own
classes: `locate` decides, and two files in different packages are two packages.

### Unresolved names

A name the compiler could not resolve is **painted**, not just underlined. A squiggle says "something
on this line is wrong"; a red identifier says *which* name —and on a line holding five names, that is
the difference between a hint and an answer.

The colour comes from the platform's own `CodeInsightColors.WRONG_REFERENCES_ATTRIBUTES`, the key
Java and Kotlin use for the same thing, so Neko needs no colour scheme of its own and inherits the
reader's theme.

What decides it is `Diagnostic.unresolved`, a flag the compiler sets in the check that failed to find
the declaration. It is *not* implied by the severity, and that distinction is load-bearing: "class
'X' implements 'Y' but does not provide 'f()'" is an error anchored on `X`, a name that resolved
perfectly well —painting it would send the reader to fix the wrong thing. Marking the four shapes
that really are unresolved (a bare name, a called name, a member name, a type name) keeps the paint
honest.

### How highlighting works

Two details are worth knowing, because both are non-obvious and both are load-bearing.

The compiler's lexer **discards** whitespace and comments —the parser has no use for either. An
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

### Package and import

A file begins with an optional `locate`, then its `import` clauses, then the classes:

```neko
locate com.github.kusa233.sample

import java.util

class Neko() {
}
```

`locate` gives the file's package; a class declared in it becomes
`com/github/kusa233/sample/Neko`. A file without one is in the unnamed package.

`import` names a **package**, or **one class** —there is no per-class alias. What an import decides is
where a name the program does *not* declare is looked for, and the lookup order is:

1. the program's own declarations **in the file's own package** —two files with the same `locate` are
   one package, so neither needs to say anything to see the other;
2. the program's own declarations in a package this file **imports** (see below);
3. `java.lang`, so `Exception` and `String` need no clause;
4. the imported packages, in source order.

Step 1 and step 2 are the same lookup with one condition between them, and that condition is the
whole of `locate`'s second job. A class in another Neko package is **not** reachable by its simple
name until the file imports it:

```neko
// other/Helper.neko
locate other.pkg

class Helper {
    static {
        var shared: int = 1
    }
}
```

```neko
// Sample.neko
locate my.pkg

import other.pkg.Helper

class Sample {
    static {
        fun main() {
            println(Helper.shared)   // legal: imported
        }
    }
}
```

Without the `import` line the name does not resolve, and the compiler says exactly what to write:

```
Sample.neko:6:21: error: unresolved reference 'Helper': it is declared in another package this file
does not import —write 'import other.pkg.Helper' to reach it
```

That diagnostic carries `suggestedImport`, so Alt+Enter writes the clause for you in the IDE —the same
mechanism a platform class already used, now covering the program's own classes.

A **qualified** name needs no import, because spelling the package out *is* saying where the name comes
from; `other.pkg.Helper.shared` works in the file above with no clause.

#### Two packages may declare the same short name

A class' name is its `locate` package plus its short name, so `demo.a.Helper` and `demo.b.Helper` are
two different classes and both may exist in one program. Which one a bare `Helper` means is decided the
way the rest of the rule already works:

* the class in **this file's own package** wins outright;
* otherwise the one from an **imported** package;
* if two *imported* packages both declare it, the name is **ambiguous** and is reported rather than
  guessed:

```
Main.neko:6:21: error: 'Helper' is ambiguous: it is declared in both 'demo.a' and 'demo.b', and this
file imports both; write 'demo.a.Helper' or 'demo.b.Helper' to say which one is meant
```

Each class is emitted into its own package directory — `demo/a/Helper.class` and `demo/b/Helper.class`
— so the two never collide on disk either.

The two spellings are the same syntax and are told apart by what the name *is*, which only the
platform can answer:

```neko
import java.util          // a package: any class in it, by simple name
import java.util.HashMap  // one class: pins HashMap, and nothing else may supply the name
```

There is no `.*`, deliberately. A wildcard would be a second way to say what the bare package import
already says, so it is refused with the form to write instead:

```
Main.neko:3:19: error: Neko has no '.*': 'import java.util' already imports everything in 'java.util',
so write 'import java.util'
```

For a package the JDK describes, the class has to really be in it: `import java.util` does not make
`BufferedReader` resolve, because `java.util` has no such class. This is what stops a typo from
becoming a reference to a class that does not exist, and it is also what lets the compiler tell you
which clause to write:

```
Main.neko:8:20: error: unknown type 'HashMap': 'HashMap' is java.util.HashMap, which this file does
not import —write 'import java.util' to reach it
```

A package the JDK does not describe —one compiled elsewhere —cannot be checked at all, so it is
taken on the file's word. That is the case an import exists for in the first place.

### Qualified names

A name may also carry its package inline, with no `import` at all:

```neko
h: java.util.HashMap = java.util.HashMap()
start: long = java.lang.System.currentTimeMillis()
out: Object? = java.lang.System.out
```

This works in a type, in a variable's declared type, and as the receiver of a call or a field read.
It is the escape hatch for the two cases an import cannot express: a class whose package the file
would rather not import at all, and a simple name that two packages both declare.

`a.b.C` and `value.member` are the same syntax, so the compiler resolves before it decides: a dotted
chain is a qualified class only when a class of exactly that name exists. Everything else —`p.x`,
`Code.OK`, `list.size` —stays a member access. The root of the chain is what settles it: a chain
starting at a class the program declares is a static member read, never a package.

Qualified names follow the same authority rule as imports. A package the JDK describes must really
hold the class, so a typo is caught:

```
Main.neko:6:29: error: 'java.util' has no class named 'Hashmap'
```

A package it does not describe is taken on the file's word, because a class compiled elsewhere cannot
be checked and refusing it would make qualified names useless for the case they exist for.

A JDK class' **static** members are resolved reflectively and dispatched against the reflected owner and
descriptor; a call that matches no overload is refused and told what the class does declare. The class
name is matched before its receiver is treated as a value, because there is no value there —and since
Neko has no static-import syntax, `Cls.member` is the only spelling available for a static member.

Only statics, though: an *instance* of a JDK class has no members here. `System.currentTimeMillis()` and
`System.out` work, while `m.size()` on a `HashMap` is refused —the JDK was never compiled by the
program, so there is no symbol to check a member against, and the message says exactly that rather than
guessing. A value the platform hands over is usable as a value: pass it on, store it, print it.

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

A parameter list may be broken across lines, and a trailing comma before the `)` is accepted — the
parentheses already say where the list ends, so neither is a statement separator. A constructor's
parameter list and a call's argument list wrap the same way:

```neko
class Neko() {
  static {
    fun add(
      a: int,
      b: int,
    ): int {
      return a + b
    }

    fun main() {
      println(add(
        1,
        2,
      ))
    }
  }
}
```

### Lambda

A lambda is `{ parameters -> body }`, and it can be written wherever a function type or a
single-method interface is expected:

```neko
f: [int] -> int = { x ->
  yield x + 1
}
```

When the parameters can be inferred from the expected type they may be written without
annotations; a lambda whose parameters cannot be inferred needs each one annotated.

#### Where a single-method interface is expected

A lambda also stands in for an interface that declares exactly one abstract method — the Java notion
of a functional interface — so it can be passed straight into a call:

```neko
interface Action {
  fun perform(times: int)
}

class Main {
  static {
    fun twice(a: Action) {
      a.perform(2)
    }

    fun main() {
      twice({ n ->
        println(n)
      })
    }
  }
}
```

The interface supplies both the parameter types and the result, exactly as a function type would, so
the body is checked against `perform`'s signature. At bytecode level the lambda becomes a class that
implements the interface and defines *its* method — `perform` here, `run` for `Runnable` — rather
than a synthetic `invoke`.

Nothing has to be written to ask for this: the parameter's declared type is the target. An interface
with two or more abstract methods is not a target, because there would be no way to say which one the
body belonged to.

It works against the Java platform's own interfaces as well, including inside a platform constructor
whose class the program never declares:

```neko
// `Thread(Runnable)`, picked out from Thread's other one-argument constructors because a lambda can
// only fit the functional-interface parameter — nothing is guessed.
t: Thread = Thread({
  println("thread body")
})
```

The generated class implements `java.lang.Runnable` and defines `run`; the method name comes from the
interface itself rather than from the compiler's convention, which is what makes the result a real
`Runnable` and not merely something that looks like one. Note that `Thread`'s own methods are still
not callable from Neko — calling an instance method of a platform type is a separate, older
limitation, and `"abc".length()` fails the same way.

#### The arrow may be left out when there are no parameters

A lambda with no parameters has nothing for the arrow to introduce, so `{ ... }` is enough:

```neko
fun main() {
  performIt({
    println("no arrow")
  })
}
```

This is decided by whether an `->` appears before the matching `}`, not by guessing from the
contents. `{ }`, `{ println(1) }` and `{ -> println(1) }` are all the same lambda; only the last one
spells out that it takes nothing. Passing a lambda whose body is several statements is the ordinary
use — a body wrapped in an arrow it does not need is the one thing the shorter form exists to avoid.

### Value class (`meow`)

`meow` before `class` declares the fields in the header and derives `equals`, `hashCode` and `toString`
from them:

```neko
meow class Point(val x: int, private val y: int) {
}
```

- The header list is the field list. Each entry is `[private|protected|public] val|var name: Type`,
  comma-separated, and there may be none: `meow class Unit()`.
- Two instances are equal when they are the same class and every field is equal. **Private fields
  take part**, as they do in a Kotlin data class.
- `==` and `!=` between two meow values use the derived `equals`. So do `a.equals(b)`,
  `a.hashCode()` and `a.toString()`: all three are ordinary methods you can call.
- `toString` prints the class name and every field in declaration order, with `string` fields quoted:

  ```neko
  println(Point(1, 2))     // Point(x=1, y=2)
  println(Cat(1, "kusa"))  // Cat(age=1, name="kusa")
  ```

- Writing your own `equals`, `hashCode` or `toString` replaces the derived one.

The header is desugared into what the language already had —a field, a constructor parameter and a
`this.x = x` —so only the three derived methods are new. They are emitted as bytecode, because Neko
has no `is` operator and no cast, so none of them could be written in Neko.

`meow` cannot be applied to an `interface` (an interface has no fields to derive from), and a meow
class must write its field list.

### Enum (`miao`)

`miao` before `class` declares a fixed set of named values:

```neko
miao class Color {
    RED,
    GREEN,
    BLUE
}
```

- The body is the constant list, one name per constant, in the order `ordinal()` reports. A comma
  separates constants, and an optional `;` closes the list before any functions.
- **The class file is a real enum**: it extends `java.lang.Enum`, carries `ACC_ENUM`, has a
  `$VALUES` array, and declares `values()` and `valueOf(string)`. So `name()`, `ordinal()`,
  `compareTo`, `equals`, `hashCode` and `toString` all come from `java.lang.Enum` and behave the way
  a Java caller expects —`println(Color.RED)` prints `RED`, not an object identity.
- `values()` answers a **copy** of the constant array, so writing into the result cannot corrupt the
  enum.
- A constant may carry values, in which case the fields are written in the header exactly as they are
  for a `meow class`, and every constant supplies its own:

  ```neko
  miao class Code(val status: int) {
      OK(200),
      GONE(410)
  }

  println(Code.OK.status)    // 200
  ```

- `==` between two constants is reference identity, which is what makes an enum usable as a set of
  tags: there is exactly one instance of each constant.

An enum cannot declare a constructor —the constants are the only instances it has, and the compiler
creates them from the constant list. It cannot extend another class, since `java.lang.Enum` is
already its superclass. It cannot override `name`, `ordinal`, `equals`, `hashCode` or `compareTo`,
which `java.lang.Enum` declares `final`; `toString` is left open. The list must name at least one
constant.

### Locking (`purr`, `purred`, `purrcls`, `purredcls`)

`purr` guards a block of statements with a lock, and gives that lock back however the block is left:

```neko
class Counter() {
    var total: int = 0

    fun add(amount: int) {
        purr {
            current: int = this.total
            this.total = current + amount
        }
    }
}
```

The lock is a `java.util.concurrent.locks.StampedLock`, which is why this is a keyword rather than a
library call: the release has to be emitted for **every** way out of the block —falling off the end,
a `return`, a `nyan`, a `yield`, and an exception thrown by anything called inside —and `unlock` also
needs the stamp the acquisition answered, which is a `long` the source cannot carry. A `synchronized`
block would need neither, but it also cannot offer a read lock.

There are two independent choices —which* lock, and *which* mode —and each combination has its own
word:

| Written | Lock | Mode |
| --- | --- | --- |
| `purr { ... }` | the object's (`this`) | exclusive |
| `purred { ... }` | the object's (`this`) | shared with other readers |
| `purrcls { ... }` | the class' | exclusive |
| `purredcls { ... }` | the class' | shared with other readers |

The class lock is one lock for the whole class, shared by every object and every `static` function, so
`purrcls { }` is what `synchronized (Cls.class)` would be and what a `static` function must use —it
has no `this` for an instance lock to belong to.

- `purred` and `purredcls` let several threads in at once, so they are for reading a value nobody may
  change meanwhile. A read-modify-write belongs under `purr`: two readers hold a read lock at the same
  time by design, so such a block is not exclusive and its updates can be lost.
- **A thread cannot take the same lock twice.** `StampedLock` is not reentrant in any mode, so
  `purr { purr { ... } }` would block forever. The mode does not help either: `purred` inside `purr` on
  the same lock is still a second acquisition. All such nestings are rejected when the program is
  analyzed rather than left to hang at run time.
- The two locks are different locks, so `purrcls { purr { ... } }` is legal and is what a class-wide
  guard around per-object work looks like.
- Blocks one after another are *not* nested: the first has released before the second is entered.

Four words rather than one keyword plus a modifier, because `class` and `read` are ordinary
identifiers in this language —reading them as modifiers in one position and as names everywhere else
would be context-dependent lexing a reader cannot see.

The locks are created on demand —a class that never writes one of these keywords carries no lock field
at all. The instance lock is a field of each object and the class lock is a `static` field, both
compiler-created because `StampedLock` lives in `java.util.concurrent.locks`, which a Neko source file
cannot name.

The nesting rule is checked *within one method body*, since that is what the analyzer can see. A
method that takes a lock and calls another method which takes the same one will hang; keeping that
apart is the programmer's job.

### Nullable types

A type followed by `?` may also be *absent*. `T?` is the only type `null` can be assigned to, and that
one rule is the whole feature: a value that may be missing says so in its type, and everything that
would dereference it is checked at compile time.

```neko
name: string? = null
name = "kusa"              // a real value is always allowed
println(name == null)      // asks whether it is absent

count: int? = 1            // a nullable primitive holds a boxed Integer
missing: string = null     // Error: 'null' needs a nullable type
```

- **Assigning.** Non-null to nullable is allowed; nullable to non-null is an error. The same holds for
  arguments, return values and array elements.
- **Dereferencing.** A nullable value cannot be read from, called on, indexed or assigned through.
  `obj.field`, `obj.f()` and `arr[i]` are all errors when `obj` or `arr` is nullable, because none of
  them can answer for the absent case. A test that settles the question narrows the value for the code
  it covers, so inside the branch the ordinary spelling works —see **Smart cast** below.
- **`?.`** is how a nullable value is used: `h?.value` is the field's value, or `null` if `h` is null,
  and `h?.f(...)` calls `f` only when `h` is there —*the arguments are not evaluated** when it is
  not. The result is nullable, so safe calls chain: `a?.b?.c`.
- **`?(...)`** is the same for a *function value*. `?.` guards the object a member is reached through,
  but a function held in a variable has no such object: the callee itself is the thing that may be
  absent. `f?(1, 2)` calls `f` only when it is there, answers `null` when it is not, and likewise does
  not evaluate the arguments.

  ```neko
  present: ([int, int] -> int)? = { x, y -> yield x + y }
  absent: ([int, int] -> int)? = null

  println(present?(1, 2))     // 3
  println(absent?(1, 2))      // null —and the call does not happen
  println(adder()?(1, 2))     // 3: the callee is the value the call answered
  ```

  As with `?.`, any `?` makes the result nullable, because a call that did not happen has no result —
  and that holds even when the callee itself cannot be absent. So `a: int = f?(1)` is an error where
  `a: int? = f?(1)` is not. Without the `?` a nullable function value cannot be called at all.
  A `?` on a call that *cannot* be absent is refused rather than ignored: a declared function is
  resolved by name (`f?(1)`), a member function (`obj.f?()`) is too, and a class name constructs
  (`Cls?()`), so in each case there is nothing to guard and the plain spelling is the only one that
  means anything.
- **Comparing.** `x == null` and `x != null` are how absence is asked about, and `==` never throws on a
  null operand: it compiles to `java.util.Objects.equals`, which answers `true` for two nulls. A
  nullable primitive compares by value, so `count == 1` works for an `int?`.
- **Carrying.** A nullable primitive is a Java reference type in the class file —`int?` is
  `java.lang.Integer` —which is what lets the absent case be represented at all. Nothing about this is
  visible in the source.

Writing `T??` is an error: a nullable nullable is the same type as a nullable one.

A function type ends in its return type, so `?` on one would attach to the return type rather than to
the function: `[int] -> int?` returns an absent `int`. To say that the *function value* may be absent,
group it —`([int] -> int)?` —and that is the spelling `f?(...)` is called through. The two are
different types, and a message about one never quotes the other's spelling.

`null` is read from its context, so it needs one: `x: string? = null` and `f(null)` where `f` declares
one parameter are fine, while `println(null)` is not —the overloads give no single parameter type to
read from, and the message says so rather than guessing. Name it first, and it prints as `null`.

`NullableSample.neko` exercises all of this, including the lines that must not compile.

### Smart cast

A test that settles whether a nullable value is there *narrows* it for the code the answer covers. It
is the same value and the same declaration; the analyzer simply knows more for a region, so the
ordinary spelling works there and no `?` is needed.

```neko
class Holder {
    val value: int

    constructor(value: int) {
        this.value = value
    }
}

h: Holder? = Holder(4)

if (h != null) {
    println(h.value)       // 4 —`h` is a Holder here, so no `?.`
} else {
    println("absent")
}

if (h == null) { return }  // or leave: what follows is the other side of the test
println(h.value)           // also fine
```

- **Which tests.** Only `== null` and `!= null`, combined with `!`, `&&` and `||`. `x != null && x.f`
  works because the second operand is reached only when the first held; `x == null || x.f` works for the
  mirror-image reason. Each arm of an `if` sees what *its* condition proves: the `then` of `x != null`
  and the `else` of `x == null` both know the value is there. A `nya`'s failure block is the careful
  case —it answers a throw as well as a false answer, so it only gets the facts of a *condition that
  cannot throw*; see [checking what the program believes](#checking-what-the-program-believes).
- **Which values.** A local or a parameter —including a `var`, since nothing outside its own function
  can write it —and a `val` field, either read through an object (`point.x`) or written bare inside the
  class. A `var` field is never narrowed: it is one place anything holding the object may write.
- **For how long.** Until the declaration is written. `h = other` drops the fact, and so does a write in
  a branch that *may* have run. Facts do not survive the branch that proved them, so a read after the
  `if` is nullable again.
- **Usable as the narrowed type.** Inside the branch the value can be used wherever the non-nullable
  type is wanted —read from, called, indexed, assigned to a `T` variable, passed to a `T` parameter,
  returned from a function that declares `T` —because on that path it really is a `T`. Leaving the
  branch, it is a `T?` again.

For an `int?` this is more than a change of spelling: the value is stored boxed, so a narrowed read is
unboxed before it is used, which is what makes `n + 1` add and `n == 7` compare by value.

Narrowing removes the need for `?.` only where a test has already answered the question. An untested
value still has to be read with `?.`, which is what makes the safe spelling the one that is always
available.

### Built-in types

The primitives are Java's eight, spelled as Neko spells them:

| Neko       | JVM descriptor | Java reference type    |
|------------|----------------|------------------------|
| `byte`     | `B`            | `java.lang.Byte`       |
| `short`    | `S`            | `java.lang.Short`      |
| `char`     | `C`            | `java.lang.Character`  |
| `int`      | `I`            | `java.lang.Integer`    |
| `long`     | `J`            | `java.lang.Long`       |
| `float`    | `F`            | `java.lang.Float`      |
| `double`   | `D`            | `java.lang.Double`     |
| `bool`     | `Z`            | `java.lang.Boolean`    |
| `string`   | —            | `java.lang.String`     |
| `Object`   | —            | `java.lang.Object`     |
| `Throwable`| —            | `java.lang.Throwable`  |

A primitive is carried as the Java reference type in the table whenever it has to be an `Object` —
inside the `Object` a `yield` throws, inside a nullable local, or as a type argument, which the JVM
erases.

Because those Java types are what the values actually become, they are also accepted as type names:

```neko
count: Integer = 1        // the same type as `int`
flag: boolean = true      // the same type as `bool`
big: Long = 1             // the same type as `long`
```

The two spellings are **one type**, not two that convert: `value: string` and `value: String` in the
same scope is a redeclaration, and an `Integer` is usable wherever an `int` is wanted. In the IDE,
completion after a `:` offers the built-ins with the Java type each one is, so `Integer` and `int`
are visibly the same thing.

### Numeric rules

Neko has **no implicit conversion between named types**, and that includes the numeric ones: there is
no Java-style widening. `x: long = someInt` is an error, `int + long` is an error, and `long + long`
is a `long`. Arithmetic and ordering require two operands of the *same* numeric type and answer that
type —switching between them is written in the source, not inferred from it.

A **literal** is the one exception, because it has no type of its own until a context gives it one:

```neko
small: byte = 1           // read as a byte
big: long = 1             // read as a long
total: long = 1 + 2       // both read as long, so this is long + long
```

A literal that does not fit the type its context asks for is reported at the literal, naming the
range, rather than being narrowed:

```neko
tooBig: byte = 300        // Error: does not fit in 'byte', whose range is -128..127
```

### String concatenation

`+` concatenates when **either** side is a `string`, and the other side may be of any type:

```neko
n: int = 42
println("n=" + n)             // n=42
println(n + " is the answer") // 42 is the answer
println("ok? " + true)        // ok? true
println("c=" + 'x')           // c=x
println("at " + 1L)           // at 1
```

`string + string` is the same rule with both sides strings. The result is always a `string`, which is
why a chain keeps concatenating: in `"x" + 1 + true` the first `+` produces a `string`, so the second
one concatenates too. Parenthesise to do arithmetic first — `"sum=" + (1 + 2)` prints `sum=3`.

This is not the numeric rule being relaxed. `1 + 2` is still arithmetic and answers an `int`; a `+`
with no string on either side is untouched, so `s: string = 1 + 2` remains an error — reported as the
initializer's type disagreeing with the declaration, which is the actual mistake.

A value of a class type joins through `String.valueOf`, so the class's own `toString` decides what it
prints. An absent value prints `null`, and `null + null` is an error: neither side says what type the
value is absent from, so there is no string to concatenate onto. A call that produces no value cannot
be concatenated either — there would be nothing to append.

### Literals

| Written        | Type     |
|----------------|----------|
| `1`            | `int`, or the type its context asks for |
| `1L`           | `long`   |
| `1.5`          | `double` |
| `1.5f`, `1.5F` | `float`  |
| `'a'`          | `char`   |
| `"text"`       | `string` |
| `true`, `false`| `bool`   |
| `null`         | the nullable type its context asks for |

The suffix is what names a fractional type: `1.5` is a `double`, and a `float` is narrower, so
`x: float = 1.5` is an error rather than a silent narrowing —write `1.5f`. A character literal
accepts the same backslash escapes a string does, so `'\n'` is one character.

`null` is the odd one out: it names no value, so it has no type of its own and is read as whatever
nullable type its context asks for. A `null` with no such context is an error rather than a guess —see
[Nullable types](#nullable-types).

### Local variables

A local's type is inferred from its initializer when you leave it out:

```neko
instance = Instance()   // instance is an Instance
count = 1               // count is an int
```

Writing the type is still allowed and still checked. A bare `name = value` is a *declaration* when
nothing is in scope under that name, and an *assignment* when something is —so `str = "new value"`
still writes to the `str` declared above it, exactly as `Sample.neko` shows. The two are told apart
by the scope alone, which is why the compiler decides it rather than the parser: a bare name may
also be a field declared further down the same class.

The inferred local is mutable, and its name is registered only *after* its value is analysed, so
`count = count + 1` with no `count` in scope reports the reference rather than quietly declaring a
variable that starts from itself.

### Loops (`for`, `for ... in`)

`while` is Java's, unchanged:

```neko
while (count < 10) {
  count++
}
```

`for` comes in two spellings. The three-part one is Java's too:

```neko
for (i: int = 0; i < 100; i++) {
  println(i)
}
```

All three parts may be left out, so `for (;;)` is an endless loop —it has no test to fail, which is
why a function whose body ends in one needs no `return`. The step may be `++`, `--`, or an assignment:
`for (i: int = 1; i < 40; i = i * 3)` is three parts of the same kind.

The other spelling walks a sequence, and `in` is the one word that tells the two apart:

```neko
for (element: int in values) {
  println(element)
}
```

Two things may be iterated:

* **An array**, walked directly. It needs no interface because the JVM gives arrays none —the
  compiler emits the length read and the element loads inline.
* **A class implementing `java.lang.Iterable`** —the JDK's interface, not a Neko one. Its
  `iterator()` must answer a `java.util.Iterator`, which is why such a file writes `import java.util`
  and not `import java.lang`. `next()` answers `Object` rather than a type parameter, because Neko
  erases generics without emitting the bridge methods a parameterised interface would need —so the
  erased shape has to *be* the declared one. The loop variable's own type still applies: the compiler
  inserts the cast (and the unboxing, for a primitive), so `for (e: int in numbers)` reads an `int`
  without the program casting anything.

Whichever form it is, the loop variable is **private to its loop** and **read-only**. Private, so two
loops in one method may each declare `i` and neither is visible afterwards. Read-only, because the
next pass rebinds it: an assignment would be computed and then discarded, so it is refused instead.

`++` and `--` are **statements, not expressions**. They produce no value, so `x = i++` cannot be
written and neither can appear inside a condition. They apply to a whole-number variable —`int`,
`long`, `short`, `byte` —and to a field reached through a receiver (`holder.count++`). `byte` and
`short` are narrowed back on the way in, exactly as an assignment to them would be.

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

A function that declares an `emit` clause says it may hand control to a `try`'s `{ ... }` block.
**`scratch` enters that block and comes back with an answer** —it is an ordinary call, not a
departure. The mechanism is a value passed alongside the arguments: a function with an `emit` clause
takes a hidden `ScratchHandlerN` as its last parameter, and `scratch` calls
`handler.dispatch(...)` on it. The clause also names, after `->`, what that handler answers.

```neko
class Neko() {
  fun invoker() {
    // Calling an `emit` function always needs a `try`, even one that never scratches: the clause is
    // what names the handler, and only a `try` declares one.
    v: int = try test() { emit1, emit2 ->
      println(emit1 * emit2)
      // `nyan` answers the `scratch` site that entered this block.
      nyan emit1 + emit2
    }
    println(v)
  }

  fun inheritEmit() {
    // A function with the *same* clause may pass the departure on unchanged, so it needs no `try`.
    test()
  } emit [int, int] -> int

  fun test(): int {
    // Reaches the block two frames up and is worth whatever it answers.
    return scratch 123, 456
  } emit [int, int] -> int
}
```

A `try` used as a value is worth what its call returned when it returned normally. The `nyan` answer
travels back to the `scratch` site, not to the `try` —so the two are independent:

**A function that declares an `emit` clause need not scratch.** The clause describes a way out that
*callers* have to provide for; whether this particular body ever takes it is its own business, and a
body that never does always answers normally:

```neko
class Neko() {
  fun test(): int {
    return 7          // Never scratches, which is fine: the clause is about what callers may expect.
  } emit [int] -> int

  fun main() {
    // The call still needs a `try`: that is where a handler would be declared if one were reached.
    v: int = try test() { x ->
      nyan x
    }
    println(v)        // 7
  }
}
```

The clause is checked in one direction only. What a `scratch` requires is a handler to reach, and a
`scratch` with none in the whole program is an error — code generation would not know which method to
call. A clause with no `scratch` behind it is not.

```neko
class Neko() {
  fun invoker() {
    // The call returns 0, so this is 0 —the handler's `nyan` went to the `scratch` site instead.
    result: int = try test(false) { emit1, emit2 ->
      nyan emit1 * emit2
    }
    println(result)
  }

  fun test(flag: bool): int {
    if (flag) {
      v: int = scratch 123, 456
      return v
    } else {
      return 0
    }
  } emit [int, int] -> int
}
```

**`yield` leaves through the `try` instead.** It throws `EmitYieldException` carrying its value, the
`try` catches it, and that value becomes what the `try` is worth —so it reaches the `try` from any
depth, however many frames down the `scratch` was:

```neko
class Neko() {
  fun invoker() {
    // The `scratch` is two frames below the `try`, and the answer the handler yields is still what
    // the `try` is worth.
    result: int = try outer() { emit1, emit2 ->
      yield 7
    }
    println(result)   // 7
  }

  fun outer(): int {
    return inner()
  } emit [int, int] -> int

  fun inner(): int {
    return scratch 1, 2
  } emit [int, int] -> int
}
```

A `yield` is what the handler does **when it runs**, so it needs a handler that runs: a `try` whose
call never scratches is worth what that call returned, and the handler's `yield` is never reached.

```neko
class Neko() {
  fun invoker() {
    // `test` returns 0 and never scratches, so the handler never runs: this is 0, not 7.
    result: int = try test() { emit1, emit2 ->
      yield 7
    }
    println(result)
  }

  fun test(): int {
    return 0
  } emit [int, int] -> int
}
```

The three keywords are not interchangeable, and the difference is the whole design:

| written | reaches | carries a value to |
| --- | --- | --- |
| `scratch v1, v2` | the handler this function was given | the site itself, as its own result |
| `nyan v` | leaves the handler | the `scratch` site that entered it |
| `yield v` | leaves by throwing | the `try` that declared the handler |

A `scratch` reaches **the handler its own function was passed**, not one found by matching a
signature. That is why several `try` blocks may answer the same `emit` clause: each builds its own
handler object and hands it to the call it wraps, so a callee serves every caller independently. It
also means a `scratch` needs no `try` anywhere in the program — the function's `emit` clause means it
already holds one.

#### A handler captures the locals around it

The `{ ... }` block becomes a method of a generated class, so a local declared where the `try` is
written is not simply in scope inside it. It is captured, exactly as a lambda captures:

```neko
class Neko() {
  fun invoker() {
    factor: int = 10
    greeting: string = "hi"
    // Both are read inside the block, so both travel in as fields of the handler object.
    v: int = try test() { a, b ->
      println(greeting)
      nyan a * b * factor
    }
    println(v)
  }
}
```

Capture is by value and by the same rule as a lambda, which means the same two restrictions apply: a
handler cannot assign to a captured local (the copy would change and the original would not), and
`this` is refused inside one, because the block runs on the handler object rather than on the object
the enclosing function was called on.

A `scratch` written inside a handler body scratches the effect further out, so the handler the
enclosing function holds is captured too. That is what lets a handler delegate: the inner site calls
the outer handler, and the answer travels back through both.

#### Naming the handler's result: `emit [int, int] -> int`

An `emit` clause names what the handler answers by writing `-> R` after the brackets:

```neko
class Neko() {
  fun source(): int {
    // Worth `int`, because the clause below declares that result.
    v: int = scratch 1, 2
    return v
  } emit [int, int] -> int

  fun invoker() {
    // A valued `nyan` must produce the declared `int`; a `string` here is an error.
    r: int = try source() { a, b -> nyan a + b }
    println(r)   // 3
  }
}
```

The result is optional. When it is written, the two halves of the clause are the two halves of one
promise: a `scratch` inside a function declaring the clause is worth `R`, so the site does not have
to work the type out from its surroundings — and a position that wants something else is reported as
one disagreement rather than two. A valued `nyan` in the matching `try` must produce an `R`.

Leaving the *value* out of a `nyan` stays legal:

```neko
r: int = try source() { a, b -> nyan }   // r is 0: `R`'s Java default
```

A bare `nyan`, or a handler body that never `nyan`s at all, answers `null`; the site reads that as
`R`'s default. So `-> R` says what a *valued* `nyan` must produce, not that every handler must
produce one.

#### Answering nothing: `emit [int]` or `emit [int] -> void`

A handler that has nothing to answer does not need a result type at all. Leaving `-> R` off says
exactly that:

```neko
class Neko() {
  fun ping() {
    scratch 7
    println("returned")
  } emit [int]

  fun invoker() {
    // The handler answers nothing, so the `try` is a statement, not a value.
    try ping() { n -> println("handled " + n) }
  }
}
```

`emit [int]` and `emit [int] -> void` mean the same thing; write `-> void` to say it out loud beside
clauses that do answer. With no result, the only `nyan` the handler admits is the bare one — a valued
`nyan` is refused, because there is no type for it to produce — and a `scratch` site is worth nothing,
so assigning it is refused too. Use this for a function whose return path carries no information: the
caller still matches it with `try`, and the values it scratches still reach the handler.

#### Leaving the value out

The `v` in `nyan v` and `yield v` is optional, and **the two vacuous forms mean different things**.

A handler always answers an `Object` — the interface says so — so a `nyan` with no value answers
`null`. A `scratch` site that wants a primitive turns that `null` into the type's Java default rather
than throwing, which is what makes "I have nothing to say" a usable answer:

```neko
class Neko() {
  fun guard(): int {
    v: int = scratch 5
    return v
  } emit [int] -> int

  fun invoker() {
    // The handler answers null, and the `int` site reads 0.
    r: int = try guard() { x ->
      nyan
    }
    println(r)   // 0
  }
}
```

A handler that ends without `nyan` at all is the same thing: the generated `dispatch` answers
`null` after the body, so the site sees the default too.

* **A bare `nyan`** answers `null`, which the site reads as `R`'s default. It is allowed whatever
  `R` is; only a *valued* `nyan` has to match the clause.
* **A bare `yield`** abandons the call without producing anything. It is accepted in a `try`
  statement, which is worth nothing, and refused where the construct is worth a value — the other path
  would produce one and this path would not.

A valued `nyan` that produces something other than the declared `R` is a compile error at the
`nyan`, which is what makes the clause checkable rather than decorative.

#### Catching a thrown exception with `try ... scratch`

The `try` spellings above all **declare** a handler. The other question you can ask is what should
happen when the call **fails**, and that is written by naming the values after the call instead of
writing a block. The handler it enters is the one the enclosing function was given — the same one its
own `scratch` sites reach — so nothing has to be declared elsewhere:

```neko
class Neko() {
  fun risky(d: int): int {
    return 100 / d
  }

  fun guarded(): int {
    // `risky(0)` throws, so the handler answers; the call's own return value is not used.
    v: int = try risky(0) scratch 1, 2
    return v
  } emit [int, int] -> int

  fun invoker() {
    // A thrown exception enters the handler, which answers `1 + 2`.
    answer: int = try guarded() { a, b -> nyan a + b }
    println(answer)   // 3
  }
}
```

A `try` whose call returns normally keeps the call's value instead:

```neko
class Neko() {
  fun risky(d: int): int {
    return 100 / d
  }

  fun guarded(): int {
    // `risky(2)` returns 50, so the construct is worth the call's value — `50`.
    v: int = try risky(2) scratch 9
    return v
  } emit [int] -> int

  fun invoker() {
    answer: int = try guarded() { a -> nyan a * 2 }
    println(answer)   // 50, not 18 — nothing was thrown
  }
}
```

The two spellings are alternatives, not layers. `try call(...) { a, b -> ... }` says **what to do when
this call scratches**; `try call(...) scratch v1, v2` says **what to do when this call throws**. So:

| written | catches | the construct is worth |
| --- | --- | --- |
| `try call(...) { a -> ... }` | the `yield` thrown by the handler it declares | the call's return value |
| `try call(...) scratch v1, v2` | the `java.lang.Exception` the call threw | the call's return value when it returned, the handler's `nyan` answer when it threw |

Four rules make the second form well defined:

* **The `scratch` is what names the destination.** It is an ordinary `scratch`, so the enclosing
  function must declare an `emit` clause and the values must match it — that clause is exactly what
  says which handler out there receives the failure. A `try ... scratch` in a function with no `emit`
  clause is refused for the same reason a bare `scratch` is.
* **The exception object is not readable.** The handler takes the values written after `scratch` and
  nothing else, so `scratch 1, 2` sends exactly the two values the author wrote. The exception is
  only the trigger.
* **Both paths must agree in type.** The call's return value and the handler's `nyan` answer are two
  answers to one question, so they have to be the same type. That also means a `void` call cannot be
  used as a value — there would be nothing on the returning path — while the statement form
  `try call() scratch 1` is fine with one.
* **Only `Exception` is caught, not `Error`.** A handler is ordinary program logic; letting it
  swallow `OutOfMemoryError` or `StackOverflowError` would turn a fatal failure into a wrong answer,
  so those still propagate.

A `yield` on its way out is **not** a failure and passes straight through — the `catch` rethrows it
untouched, so wrapping a call in this form never diverts an effect travelling to another `try`:

```neko
class Neko() {
  fun inner(): int {
    return scratch 21
  } emit [int] -> int

  fun outer(): int {
    // The `yield` `inner` throws passes through this form and reaches `invoker`'s handler, so this
    // is answered by `nyan a + 1` and not by the `scratch 99` written here.
    w: int = try inner() scratch 99
    return w
  } emit [int] -> int

  fun invoker() {
    answer: int = try outer() { a -> nyan a + 1 }
    println(answer)   // 22
  }
}
```

### Checking what the program believes

```neko
count: int = 3
nya count > 0                              // carries on
nya count == 4                             // AssertionError: count == 4

nya count == 5 {                           // the failure is handled, and the program carries on
  println("count was not 5")
}

nya close() {                              // no `bool` needed: the block answers a throw
  println("close() failed")
}

v: int = nya count == 3 {                  // worth a value: one block per outcome
  yield 0                                  // the condition was false, or threw
} else {
  yield 1                                  // the condition ran to a true answer
}
```

`nya <condition>` writes down something the program believes, and says what a failure means. Bare, the
condition is an ordinary `bool` expression —a comparison, a `bool` variable, a call —so `nya` only
ever adds "and this must be true" to whatever you already wrote.

The three forms differ in what a failure is worth, not in how the test is compiled:

- **`nya condition`** —a false condition throws `java.lang.AssertionError`. **The message is the
  condition's own source text**, so a failure reads `count == 4` rather than a message written out
  again, and the report cannot drift from the check.
- **`nya condition { ... }`** —the block runs instead of throwing, and **control carries on after
  it**. This is what the language uses in place of `try`/`catch`: a failure that can be dealt with is
  dealt with where it happened, and the program continues. The block is the answer to **two** kinds of
  failure: a condition that came back `false`, and an `AssertionError` thrown while the condition was
  being evaluated —its own bare `nya`, or one inside a function it called. So `nya method() { ... }`
  is a call whose failed checks are handled at the call site, and the condition **does not have to be
  a `bool`** when a block says what failing means: `void` has no false to test, which is exactly why
  the throw is the only way in.
- **`nya condition { ... } else { ... }`** —the construct is worth a value. The `else` block yields
  it when the condition ran to a true answer and the failure block yields it when it was false or
  threw; both must yield, and both must yield the same type. A lone failure block in value position is
  refused, because there would be nothing to say what the expression is worth when the condition
  holds.

Everything else follows from the condition:

- **The check is always on.** Unlike Java's `assert` there is no `-ea` flag to remember; a `nya` that
  silently did nothing would be worse than no `nya` at all.
- **A bare check narrows like a guard.** Only the reading where the condition held reaches the next
  statement, so `nya h != null` proves `h` is there for everything after it —exactly as
  `if (h == null) { return }` does, so `h.value` needs no `?.` afterwards. A check *with* a failure
  block does not narrow afterwards: control gets there either way, so nothing is proved by then.
- **A failure block only believes what both ways in prove.** The `else` block of a value form is
  entered only when the condition ran to an answer, so it may take the condition's own reading —
  `nya h != null { ... } else { h.value }` needs no `?.` in the `else`. The failure block is reached
  by a throw as well, so it gets those facts only when the condition cannot throw: in
  `nya h == null || ready() { ... }` a throw out of `ready()` arrives with `h` still null, so `h.value`
  there is refused and needs a test of its own. Pure comparisons, names and literals cannot throw, so
  the ordinary `nya h == null { ... }` still narrows as you would expect.

`nya` is deliberately not a function call: what it lowers to is not a call but a branch that either
throws or runs a block —plus, when there is a block, a handler that catches the `AssertionError` the
condition may throw, covering the condition and nothing else.

The bare form is compiled exactly the way Java's own `assert` is: branch **past** the throw, and let
the throw follow the branch.

```
cond; ifne passed          // the whole cost of a check that holds
throw new AssertionError("cond")
passed:
```

The two forms with a block cannot keep that shape —an `else` body, a failure block and a handler all
have to converge on one point afterwards —so they branch *to* the failure and jump over it. Both
shapes are pinned by tests that read the emitted instructions, because a jump that lands on the next
instruction runs identically to no jump at all; only the bytecode tells them apart.
