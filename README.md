# Neko language

Neko is a toy JVM language for experimenting with compilers: it has classes, interfaces,
`emit`/`scratch`/`nyan` error control, and it compiles straight to Java bytecode.

## Project layout

The build is a Gradle multi-module project. The compiler and the IDE plugin are separate modules so
that using one does not drag in the other: the plugin depends on the compiler, never the reverse.

| Path            | Module         | What it is                                                   |
|-----------------|----------------|--------------------------------------------------------------|
| `neko/`         | `:neko`        | The compiler: lexer, parser, AST, analyzer, bytecode emitter  |
| `plugin/idea/`  | `:plugin:idea` | NekoJ, the IntelliJ IDEA plugin, built on `:neko`             |
| `stdlib/`       | —              | The Neko standard library, compiled by the compiler itself     |
| `samples/`      | —              | Example programs                                              |
| `neko-compile/` | —              | Compiler output; not checked in                               |

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
./gradlew :neko:runNeko -Pneko.program=ExceptionSample

# Tests.
./gradlew :neko:test                     # the compiler: 444 tests
./gradlew :plugin:idea:test              # the plugin: 51 tests, in a real IDE instance
```

Compiled classes are written to `neko-compile/classes`, relative to the project directory. The path
and the directories that are compiled come from `gradle.properties`:

```properties
neko.sourceDirectories=samples/com/github/kusa233/neko/sample
neko.outputDirectory=neko-compile/classes
```

Every directory listed is searched recursively, so adding a source file means adding it to a
directory.

`ExceptionSample` prints:

```
123
456
1
```

`NullableSample` exercises the nullable feature end to end, and its comments mark the lines that must
*not* compile as clearly as the ones that must — the errors are as much a part of the sample as the
output. It is the closest thing to a reference for [nullable types](#nullable-types):

```bash
./gradlew :neko:runNeko -Pneko.program=NullableSample
```

`NyaSample` does the same for [`nya`](#checking-what-the-program-believes), and `ExceptionSample` for
`scratch`, `nyan` and `try`. `EffectRecovery` is the reference for the same mechanism — the one the
`emit`/`scratch`/`nyan` section below walks through. `EnumSample` shows [`miao class`](#enum-miao) —
the constants, the values they can carry, and `values()`/`valueOf()` — and `PurrSample` shows
[locking](#locking-purr-purred-purrcls-purredcls):

```bash
./gradlew :neko:runNeko -Pneko.program=NyaSample
./gradlew :neko:runNeko -Pneko.program=ExceptionSample
./gradlew :neko:runNeko -Pneko.program=EffectRecovery
./gradlew :neko:runNeko -Pneko.program=EnumSample
./gradlew :neko:runNeko -Pneko.program=PurrSample
```

## Standard library

The library is written in Neko, in `stdlib/`, and **the compiler carries it**: every compilation
prepends it to whatever program it was asked to build. A program therefore never names it, and a
caller never passes it — `gradlew compileNeko`, the command line, the IDE and the tests all get it by
doing nothing.

Its sources are packaged into the compiler's own jar as resources rather than read from `stdlib/` at
run time. That is what makes a built jar usable from another machine, from the plugin (which receives
the compiler as a jar on its classpath) and from a test run anywhere — none of which can be assumed to
sit next to this repository. `StandardLibrary` reads them back by a path that resolves the same way
whether they came from a jar or from a directory of class files.

The library holds only what the language cannot borrow from the JVM:

* `EmitYieldException` — the carrier a `yield` throws. It is checked rather than unchecked on purpose:
  a `yield` is only legal inside a handler a `try` declared, so every method between them has to say
  it may pass one on. That is what makes the effect visible in the class file instead of a surprise at
  run time. Its one field is `public` — the code that reads it is the `try`'s generated handler, in a
  *different* class, so `private` would compile cleanly and fail with `IllegalAccessError` the first
  time a `yield` fired.

Everything else the effect mechanism needs is generated: a `try`'s `{ ... }` block becomes a `static`
method of the class holding the `try`, and `scratch` compiles to an ordinary `invokestatic` against it.

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
without emitting the bridge methods a parameterised interface needs — `fun next(): int` would compile
to `()I` while `Iterator<T>` declares `()Ljava/lang/Object;`, and the two would never meet. So `Object`
is not a workaround against the JDK, it *is* the erased signature; `for` inserts the cast and the
unboxing, and the loop variable's declared type is still what the body reads.

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

### What completion offers

The list is the compiler's own symbol table, not a hand-written catalogue, so a name appears exactly
when it exists:

- **Locals in scope** — the variables declared above the caret in the enclosing blocks, and the
  parameters of the enclosing function, lambda or `try` extractor. A variable
  declared *below* the caret is not offered, because writing it there would not compile.
- **The enclosing class' own members** — the static ones in a static body, and the instance ones as
  well in an instance body. Members of other classes are not offered bare, because reaching one
  needs a receiver.
- **Classes** — the program's own, and the Java platform's. The platform's are read from the running
  JDK rather than listed, and only the ones this file can actually name are offered: `java.lang`,
  and the file's imported packages.
- **Types, in a type position** — after a `:`, and inside an `emit [ ... ]` clause. A type position
  gets its own list, since a variable is not a type.
- **The built-ins**, each shown with the Java type it is, so `Integer` and `int` are visibly the same
  type rather than two.

### Automatic imports

A class the file cannot name yet is offered too — with the `import` clause that would make it
nameable, written for you when you accept it. Type `Hash`, pick `HashMap` from the popup, and the
file gains `import java.util` as well as the name.

This depends on three things agreeing, which is why it is built the way it is:

- **The index is the compiler's.** `PlatformLibrary` reads the package and class list out of the
  running JDK's `java.base` module, so a name is offered exactly when the analyzer would resolve it.
  No table is kept, because a JDK gains and loses classes between releases.
- **The fix is the compiler's.** The analyzer reports an unresolvable platform class as an error
  carrying `suggestedImport`, and the IDE turns that field into an Alt+Enter action. Re-deriving the
  clause in the plugin would be a second implementation free to disagree with the one that decides
  whether the file compiles.
- **The placement is the file's.** A Neko header is ordered — `locate`, then every `import`, then the
  classes — so `NekoImports` works out where the clause goes from the file's own text rather than
  from a line number, and refuses to write a clause the file already has (a second identical import
  is an error).

Classes needing an import are offered **only once a prefix has been typed**. `java.base` holds 3373
classes; listing them all on an empty prefix both overflows the popup and buries the handful of names
the reader was reaching for. `java.lang` and already-imported packages need no clause, so they are
offered regardless.

Note what the language itself requires: **the import is compulsory**. `HashMap` without
`import java.util` is an error, not a warning — the compiler cannot know a class from a package it was
never told to look in, and resolving it silently would make the name's package invisible in the source.
The IDE writes the clause; it does not stand in for one.

### Unresolved names

A name the compiler could not resolve is **painted**, not just underlined. A squiggle says "something
on this line is wrong"; a red identifier says *which* name — and on a line holding five names, that is
the difference between a hint and an answer.

The colour comes from the platform's own `CodeInsightColors.WRONG_REFERENCES_ATTRIBUTES`, the key
Java and Kotlin use for the same thing, so Neko needs no colour scheme of its own and inherits the
reader's theme.

What decides it is `Diagnostic.unresolved`, a flag the compiler sets in the check that failed to find
the declaration. It is *not* implied by the severity, and that distinction is load-bearing: "class
'X' implements 'Y' but does not provide 'f()'" is an error anchored on `X`, a name that resolved
perfectly well — painting it would send the reader to fix the wrong thing. Marking the four shapes
that really are unresolved (a bare name, a called name, a member name, a type name) keeps the paint
honest.

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

`import` names a **package**, not a class — there is no per-class alias. What an import decides is
where a name the program does *not* declare is looked for, and the lookup order is:

1. the program's own declarations, by simple name, whichever package they are in — the unit of
   compilation is the whole program, so this always answers first and an import can only ever *add* a
   way to resolve a name;
2. `java.lang`, so `Exception` and `String` need no clause;
3. the imported packages, in source order.

For a package the JDK describes, the class has to really be in it: `import java.util` does not make
`BufferedReader` resolve, because `java.util` has no such class. This is what stops a typo from
becoming a reference to a class that does not exist, and it is also what lets the compiler tell you
which clause to write:

```
Main.neko:8:20: error: unknown type 'HashMap': 'HashMap' is java.util.HashMap, which this file does
not import — write 'import java.util' to reach it
```

A package the JDK does not describe — one compiled elsewhere — cannot be checked at all, so it is
taken on the file's word. That is the case an import exists for in the first place.

The same lookup decides the receiver of a member access, which is what makes a JDK **static** member
reachable:

```neko
start: long = System.currentTimeMillis()
d: double = Math.abs(-3.5)
out: Object = System.out
```

The class name is matched before the receiver is treated as a value, because there is no value there.
Neko has neither static-import syntax nor qualified type syntax, so `Cls.member` is the only spelling
available — which means recognising a class name as a receiver is not a convenience but the whole
mechanism. Members of a JDK class are resolved reflectively and dispatched against the reflected
owner and descriptor; a call that matches no overload is refused and told what the class does declare.

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

The header is desugared into what the language already had — a field, a constructor parameter and a
`this.x = x` — so only the three derived methods are new. They are emitted as bytecode, because Neko
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
  a Java caller expects — `println(Color.RED)` prints `RED`, not an object identity.
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

An enum cannot declare a constructor — the constants are the only instances it has, and the compiler
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
library call: the release has to be emitted for **every** way out of the block — falling off the end,
a `return`, a `nyan`, a `yield`, and an exception thrown by anything called inside — and `unlock` also
needs the stamp the acquisition answered, which is a `long` the source cannot carry. A `synchronized`
block would need neither, but it also cannot offer a read lock.

There are two independent choices — *which* lock, and *which* mode — and each combination has its own
word:

| Written | Lock | Mode |
| --- | --- | --- |
| `purr { ... }` | the object's (`this`) | exclusive |
| `purred { ... }` | the object's (`this`) | shared with other readers |
| `purrcls { ... }` | the class' | exclusive |
| `purredcls { ... }` | the class' | shared with other readers |

The class lock is one lock for the whole class, shared by every object and every `static` function, so
`purrcls { }` is what `synchronized (Cls.class)` would be and what a `static` function must use — it
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
identifiers in this language — reading them as modifiers in one position and as names everywhere else
would be context-dependent lexing a reader cannot see.

The locks are created on demand — a class that never writes one of these keywords carries no lock field
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
  it covers, so inside the branch the ordinary spelling works — see **Smart cast** below.
- **`?.`** is how a nullable value is used: `h?.value` is the field's value, or `null` if `h` is null,
  and `h?.f(...)` calls `f` only when `h` is there — **the arguments are not evaluated** when it is
  not. The result is nullable, so safe calls chain: `a?.b?.c`.
- **`?(...)`** is the same for a *function value*. `?.` guards the object a member is reached through,
  but a function held in a variable has no such object: the callee itself is the thing that may be
  absent. `f?(1, 2)` calls `f` only when it is there, answers `null` when it is not, and likewise does
  not evaluate the arguments.

  ```neko
  present: ([int, int] -> int)? = { x, y -> yield x + y }
  absent: ([int, int] -> int)? = null

  println(present?(1, 2))     // 3
  println(absent?(1, 2))      // null — and the call does not happen
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
- **Carrying.** A nullable primitive is a Java reference type in the class file — `int?` is
  `java.lang.Integer` — which is what lets the absent case be represented at all. Nothing about this is
  visible in the source.

Writing `T??` is an error: a nullable nullable is the same type as a nullable one.

A function type ends in its return type, so `?` on one would attach to the return type rather than to
the function: `[int] -> int?` returns an absent `int`. To say that the *function value* may be absent,
group it — `([int] -> int)?` — and that is the spelling `f?(...)` is called through. The two are
different types, and a message about one never quotes the other's spelling.

`null` is read from its context, so it needs one: `x: string? = null` and `f(null)` where `f` declares
one parameter are fine, while `println(null)` is not — the overloads give no single parameter type to
read from, and the message says so rather than guessing. Name it first, and it prints as `null`.

`NullableSample.neko` exercises all of this, including the lines that must not compile.

### Smart cast

A test that settles whether a nullable value is there *narrows* it for the code the answer covers. It
is the same value and the same declaration; the analyzer simply knows more for a region, so the
ordinary spelling works there and no `?` is needed.

```neko
h: Holder? = Holder(4)

if (h != null) {
    println(h.value)       // 4 — `h` is a Holder here, so no `?.`
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
  case — it answers a throw as well as a false answer, so it only gets the facts of a *condition that
  cannot throw*; see [checking what the program believes](#checking-what-the-program-believes).
- **Which values.** A local or a parameter — including a `var`, since nothing outside its own function
  can write it — and a `val` field, either read through an object (`point.x`) or written bare inside the
  class. A `var` field is never narrowed: it is one place anything holding the object may write.
- **For how long.** Until the declaration is written. `h = other` drops the fact, and so does a write in
  a branch that *may* have run. Facts do not survive the branch that proved them, so a read after the
  `if` is nullable again.
- **Usable as the narrowed type.** Inside the branch the value can be used wherever the non-nullable
  type is wanted — read from, called, indexed, assigned to a `T` variable, passed to a `T` parameter,
  returned from a function that declares `T` — because on that path it really is a `T`. Leaving the
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
| `string`   | —              | `java.lang.String`     |
| `Object`   | —              | `java.lang.Object`     |
| `Throwable`| —              | `java.lang.Throwable`  |

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
type — switching between them is written in the source, not inferred from it.

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
`x: float = 1.5` is an error rather than a silent narrowing — write `1.5f`. A character literal
accepts the same backslash escapes a string does, so `'\n'` is one character.

`null` is the odd one out: it names no value, so it has no type of its own and is read as whatever
nullable type its context asks for. A `null` with no such context is an error rather than a guess — see
[Nullable types](#nullable-types).

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

All three parts may be left out, so `for (;;)` is an endless loop — it has no test to fail, which is
why a function whose body ends in one needs no `return`. The step may be `++`, `--`, or an assignment:
`for (i: int = 1; i < 40; i = i * 3)` is three parts of the same kind.

The other spelling walks a sequence, and `in` is the one word that tells the two apart:

```neko
for (element: int in values) {
  println(element)
}
```

Two things may be iterated:

* **An array**, walked directly. It needs no interface because the JVM gives arrays none — the
  compiler emits the length read and the element loads inline.
* **A class implementing `java.lang.Iterable`** — the JDK's interface, not a Neko one. Its
  `iterator()` must answer a `java.util.Iterator`, which is why such a file writes `import java.util`
  and not `import java.lang`. `next()` answers `Object` rather than a type parameter, because Neko
  erases generics without emitting the bridge methods a parameterised interface would need — so the
  erased shape has to *be* the declared one. The loop variable's own type still applies: the compiler
  inserts the cast (and the unboxing, for a primitive), so `for (e: int in numbers)` reads an `int`
  without the program casting anything.

Whichever form it is, the loop variable is **private to its loop** and **read-only**. Private, so two
loops in one method may each declare `i` and neither is visible afterwards. Read-only, because the
next pass rebinds it: an assignment would be computed and then discarded, so it is refused instead.

`++` and `--` are **statements, not expressions**. They produce no value, so `x = i++` cannot be
written and neither can appear inside a condition. They apply to a whole-number variable — `int`,
`long`, `short`, `byte` — and to a field reached through a receiver (`holder.count++`). `byte` and
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
**`scratch` enters that block and comes back with an answer** — it is an ordinary call, not a
departure. The block is compiled into a `static` method, so `scratch` is an `invokestatic`:

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
  } emit [int, int]

  fun test(): int {
    // Reaches the block two frames up and is worth whatever it answers.
    return scratch 123, 456
  } emit [int, int]
}
```

A `try` used as a value is worth what its call returned when it returned normally. The `nyan` answer
travels back to the `scratch` site, not to the `try` — so the two are independent:

```neko
class Neko() {
  fun invoker() {
    // The call returns 0, so this is 0 — the handler's `nyan` went to the `scratch` site instead.
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
  } emit [int, int]
}
```

**`yield` leaves through the `try` instead.** It throws `EmitYieldException` carrying its value, the
`try` catches it, and that value becomes what the `try` is worth — so it works from any depth, and a
`try` that is used as a value can be answered even by a call that never scratches:

```neko
class Neko() {
  fun invoker() {
    // `test` never scratches, but the handler yields, so the `try` is worth 7.
    result: int = try test() { emit1, emit2 ->
      yield 7
    }
    println(result)
  }

  fun test(): int {
    return 0
  } emit [int, int]
}
```

The three keywords are not interchangeable, and the difference is the whole design:

| written | reaches | carries a value to |
| --- | --- | --- |
| `scratch v1, v2` | the handler whose `emit` clause matches | the site itself, as its own result |
| `nyan v` | leaves the handler | the `scratch` site that entered it |
| `yield v` | leaves by throwing | the `try` that declared the handler |

A `scratch` is matched to a handler by its `emit` signature alone, so **each signature may have only
one handler in the program** — two would be indistinguishable from a site's point of view, and that is
reported rather than resolved by declaration order.

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
condition is an ordinary `bool` expression — a comparison, a `bool` variable, a call — so `nya` only
ever adds "and this must be true" to whatever you already wrote.

The three forms differ in what a failure is worth, not in how the test is compiled:

- **`nya condition`** — a false condition throws `java.lang.AssertionError`. **The message is the
  condition's own source text**, so a failure reads `count == 4` rather than a message written out
  again, and the report cannot drift from the check.
- **`nya condition { ... }`** — the block runs instead of throwing, and **control carries on after
  it**. This is what the language uses in place of `try`/`catch`: a failure that can be dealt with is
  dealt with where it happened, and the program continues. The block is the answer to **two** kinds of
  failure: a condition that came back `false`, and an `AssertionError` thrown while the condition was
  being evaluated — its own bare `nya`, or one inside a function it called. So `nya method() { ... }`
  is a call whose failed checks are handled at the call site, and the condition **does not have to be
  a `bool`** when a block says what failing means: `void` has no false to test, which is exactly why
  the throw is the only way in.
- **`nya condition { ... } else { ... }`** — the construct is worth a value. The `else` block yields
  it when the condition ran to a true answer and the failure block yields it when it was false or
  threw; both must yield, and both must yield the same type. A lone failure block in value position is
  refused, because there would be nothing to say what the expression is worth when the condition
  holds.

Everything else follows from the condition:

- **The check is always on.** Unlike Java's `assert` there is no `-ea` flag to remember; a `nya` that
  silently did nothing would be worse than no `nya` at all.
- **A bare check narrows like a guard.** Only the reading where the condition held reaches the next
  statement, so `nya h != null` proves `h` is there for everything after it — exactly as
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
throws or runs a block — plus, when there is a block, a handler that catches the `AssertionError` the
condition may throw, covering the condition and nothing else.

The bare form is compiled exactly the way Java's own `assert` is: branch **past** the throw, and let
the throw follow the branch.

```
cond; ifne passed          // the whole cost of a check that holds
throw new AssertionError("cond")
passed:
```

The two forms with a block cannot keep that shape — an `else` body, a failure block and a handler all
have to converge on one point afterwards — so they branch *to* the failure and jump over it. Both
shapes are pinned by tests that read the emitted instructions, because a jump that lands on the next
instruction runs identically to no jump at all; only the bytecode tells them apart.
