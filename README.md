# Neko

A compiler for the Neko language, written in Kotlin. It reads `.neko` source text and emits Java
`.class` files that run on an ordinary JVM.

The input it is specified against is [`Sample.neko`](samples/com/github/kusa233/neko/sample/Sample.neko),
whose own comments state which lines must compile and which must not, together with
[`ExceptionSample.neko`](samples/com/github/kusa233/neko/sample/ExceptionSample.neko), whose comments
give the exact Java lowering that `raise`/`emit`/`try` must produce.

```
source text ──▶ Lexer ──▶ Parser ──▶ Analyzer ──▶ BytecodeGenerator ──▶ .class files
```

Every stage reports into one `DiagnosticBag`, so a single run surfaces as many independent problems
as possible instead of stopping at the first one.

## Building and running the samples

```bash
./gradlew compileNeko                          # compiles every configured directory
./gradlew runNeko                              # runs Sample
./gradlew runNeko -Pneko.program=ExceptionSample   # runs ExceptionSample; prints 123, 456 then 1
./gradlew test                                   # runs the test suite
```

`compileNeko` writes to `neko-compile/classes`, **relative to the project directory**, so the
class files stay inside the repository (`D:\Codes\Kotlin\Apsars\neko-compile\classes` on a Windows
checkout) rather than at the root of whatever drive the build runs on. That directory is a build
product and is git-ignored.

The tasks are handed the absolute location, resolved against the project directory, because a Gradle
daemon may be running with a different working directory than the project: if the compiler resolved
`neko-compile` against its own working directory while the run task resolved it against the
project's, the two would disagree about where the classes are.

Passing a relative path to the CLI works the same way — it resolves against the current directory:

```bash
java -cp <classpath> com.github.kusa233.neko.MainKt Sample.neko --output neko-compile/classes
```

The compiled samples print exactly what their comments promise. `Sample`:

```
123
Hell world!
new value
a more than b and more than c
Correct a
```

and `ExceptionSample`, whose `raise 123, 456` carries both values to the extractor, and whose
`yield 1` is what the `try` expression is worth, so `println(result)` prints it:

```
123
456
1
```

### Where the sources come from

**No `.neko` file is ever named in `build.gradle.kts`.** The build is told which *directories* hold
sources, and every `.neko` file found under them is compiled in one run; adding a source file means
adding it to a directory. The configuration lives in
[`gradle.properties`](gradle.properties):

```properties
neko.libraryDirectories=samples/library
neko.sourceDirectories=samples/com/github/kusa233/neko/sample
neko.outputDirectory=neko-compile/classes
neko.mainClass=com.github.kusa233.neko.sample.Sample
```

`neko.libraryDirectories` is the standard library every program compiles against, and
`neko.sourceDirectories` holds the programs themselves. Both are compiled together — the split is
about intent, not about which class files come out. Directories are relative to the project
directory and searched recursively, so a file's location inside one is a matter of tidiness; the
package a file lands in is decided by its own `locate` line.

Compilation is per **program**, not per file: a class in one file may name a class declared in
another. That is what lets the standard library be ordinary Neko sources rather than pre-compiled
classes.

### Command line

```
neko <path>... [--output <directory>] [--color|--no-color]
```

Each path may be a file or a directory; a directory is searched recursively for `.neko` files.
Exit codes: `0` success, `1` compilation errors or a bad invocation, `2` an internal compiler error.
Diagnostics are coloured only on an interactive terminal; `NO_COLOR` and `FORCE_COLOR` are honoured.

## Diagnostics

Errors are reported at the exact token they concern, with the offending source quoted and underlined,
and with a note carrying any secondary location that makes the message actionable:

```
Sample.neko:17:9: error: variable 'str' is already declared in this scope
   |
17 |         str: string = "new value"
   |         ^^^
Sample.neko:14:9: note: previous declaration of 'str' is here
   |
14 |         str: string = "Hell world!"
   |         ^^^
```

That output is produced by uncommenting line 17 of the sample, which sits under `// Error to compile:`
precisely because line 14 already declared `str`. The position comes from the `Span` carried on every
token and every AST node, so the compiler points at *the offending expression* rather than at the
enclosing line.

A note carries the secondary location that makes the message actionable, whichever rule produced it.
An instance member reached from a static function, and a statement that can never run:

```
Counter.neko:4:17: error: field 'count' is an instance member of Counter, so it cannot be used
from a static context; create an object first and write 'obj.count'
   |
 4 |         println(count)
   |                 ^^^^^
```

```
Emitter.neko:4:9: error: unreachable statement: execution cannot continue past the preceding 'raise'
   |
 4 |         println("never runs")
   |         ^^^^^^^^^^^^^^^^^^^^^
Emitter.neko:3:9: note: the previous statement is here
   |
 3 |         raise 1
   |         ^^^^^^^
```

## The language

```neko
locate com.example            // package; class names resolve within it

import com.example.helpers    // optional: another package names may come from

class Sample {
    private static val field1: int = 123   // static: one per class, initializers run in <clinit>

    static fun main(args: string[]) {
        println(field1)             // built-in, overloaded on int/string/bool/Object
        str: string = "Hell world!" // no `val`, so this is mutable
        str = "new value"           // ... which is why this compiles
    }
}
```

**Types.** `int`, `bool`, `string`, `Object`, `Throwable`, `T[]`, function types
`[int, string] -> bool`, and class types. A name starting with a capital letter is resolved against
the program's own declarations — so a class the program declares, and any of the standard library's,
is reachable from any package — then against `java.lang`, and finally against the file's own package.

**Declarations.** `val` is immutable, `var` is mutable, and a declaration with no keyword is mutable.
A local is *not* in scope inside its own initializer, so `lambda: int = lambda()(1, 2)` calls the
function `lambda`, as the sample relies on.

**Static and instance members.** A member without `static` belongs to an *object*, not to the class.
It is reachable only through an object, and the object has to be created first:

```neko
class Counter {
    var count: int = 0          // an instance field: each Counter has its own
    fun bump() {                // an instance function
        count = count + 1       // inside an instance member, `count` means `this.count`
    }
    static val limit: int = 9   // a static field: one per class
}

class Main {
    static fun main() {
        c: Counter = Counter()  // the object is created here...
        c.bump()                // ... so its members are reachable here
        println(c.count)        // 1
        println(Counter.limit)  // a static member needs no object; the class name will do
    }
}
```

A bare reference to an instance member from a `static` function is an **error**, and the message says
what to write instead (`create an object first and write 'obj.name'`). The rule applies in both
directions: a `static` member reached through an object is rejected too, because the class name reads
better and does not require an instance. Inside an instance member there *is* a receiver — the object
the call arrived on — so the bare name resolves to it. A lambda body is not an instance member of its
enclosing class, so that implicit `this` is withdrawn there.

Every class gets a constructor, and each object runs the non-`static` field initializers itself, so
two objects never share a value. `static` field initializers run once, in `<clinit>`.

**Inheritance and interfaces.** A class header names a superclass to extend and any interfaces to
implement:

```neko
interface Shape {
    fun area(): int                  // no body: abstract
    fun describe(): string {         // a body: a default method
        return "a shape"
    }
}

class Square: Shape {
    private val side: int

    constructor(side: int) {
        this.side = side
    }

    fun area(): int {
        return this.side * this.side
    }
}
```

A `val` field is writable **only inside its own class' constructor**, which is what makes
`this.side = side` legal and a later write an error. Because Neko has no `super(...)` syntax, a
superclass must be constructible with no arguments — a superclass that requires one is rejected
rather than compiled into a class the verifier refuses. A class that leaves an interface method
unimplemented is rejected too. A class is emitted `final` only when nothing extends it.

**Control flow.** `if`/`else`, `while`, and `when`:

```neko
when (a) {
    3 -> println("three")
    2 -> { println("two") }        // a branch body may be a block
    else -> println("other")
}
```

The subject is evaluated once, not once per branch.

**Chained comparisons.** `a > b > c` means `a > b && b > c`, and each operand is evaluated exactly
once — the middle one is not recomputed as a naive desugaring would.

**Lambdas.** `{ x, y -> yield x + y }` implements a generated interface, one per distinct function
type. Variables are captured **by value**, so assigning to a captured variable from inside a lambda
is rejected rather than silently ignored.

**Exceptions.** Neko has no `throw` and no `throws` — neither is a keyword, and writing either is
reported with what to write instead. A function leaves early with `raise`, which the caller receives
with `try` (see *Early emit* below); to let a throwable from another JVM language out, there is
nothing to write, because an uncaught exception already propagates. To *handle* one, `try` takes the
familiar block form:

```neko
try {
    println(1 / zero())
} catch (e: ArithmeticException) {
    println("caught")
} finally {
    println("finally")
}
```

`catch` accepts only a type the JVM could actually throw into it — checked against the class
hierarchy, so a program-declared exception must extend `Throwable`, and a platform one such as
`ArithmeticException` is accepted because the JDK says it is throwable. `Throwable` itself is the
catch-all.

**Early emit.** A function can hand its results back by *leaving early* rather than returning, and
the caller receives them at the call site. The function declares an `emit` clause naming the types it
will hand back, writes `raise` where it leaves, and the caller writes `try`:

```neko
static fun test(multiplier: int) {
    raise 1 * multiplier, multiplier
} emit [int, int]

static fun main() {
    try test(10) { first, second ->    // the values, in the order `raise` named them
        println(first)                 // 10
        println(second)                // 10
    }
}
```

The clause is written with **square brackets**, and `emit []` — a function that raises no values but
still leaves early — is a legal clause, distinct from having none.

A function with an `emit` clause may also declare a return type, because the two paths are different
ways out: `return` answers the caller directly, `raise` hands the values to the caller's extractor.

Calling such a function anywhere other than a `try` extractor is an error, reported at the call:

```
Emitter.neko:9:9: error: 'test' declares an 'emit' clause, so it never returns a value; call it as
'try test(...) { ... -> }' to receive the values it 'raise's
   |
 9 |         test()
   |         ^^^^^^
```

There is one exception, and it is what makes wrappers possible: a caller that declares the *same*
`emit` clause is not receiving the values, it is passing them on, so the call needs no `try`:

```neko
static fun inner(multiplier: int) {
    raise 2 * multiplier, multiplier
} emit [int, int]

static fun outer(multiplier: int) {
    inner(multiplier)          // no `try`: the clause matches, so the raise travels on
} emit [int, int]
```

The clauses must match **exactly**, type for type. `emit [int]` forwarding into `emit [Object]` would
not be a pass-through — it would need a conversion, and a `try` is where that belongs.

This is the sample's own construct, and the lowering is the one its comments spell out:

```java
// try test(10) { first, second -> ... }  becomes a try/catch around the call
EmitExtractor2 action = (o0, o1) -> { ... };
try { test(10); } catch (EarlyEmitException e) { e.doEmit(action); }

// raise 1 * multiplier, multiplier  becomes a thrown carrier
private static void test(int multiplier) throws EarlyEmitException {
    throw new EarlyEmitException(test$emit$0(multiplier));
}
private static Object[] test$emit$0(int multiplier) {   // one argument per raised value
    Object[] emitTable = new Object[2];
    emitTable[0] = 1 * multiplier;
    emitTable[1] = multiplier;
    return emitTable;
}
```

The values are boxed into an `Object[]` by a generated `private static` builder — named
`<function>$emit$<n>` after its function — because the values have different descriptors and one
carrier type has to hold all of them.

**A `try` is worth a value.** The statement form above discards what the extractor found. Writing the
call in a value position gives it a result instead, and the extractor says what that result is with
`yield`:

```neko
static fun test(flag: bool): int {
    if (flag) {
        raise 123, 456
    } else {
        return 789
    }
} emit [int, int]

static fun main() {
    result: int = try test(true) { first, second ->
        println(first)                 // 123
        println(second)                // 456
        yield 1                        // what `result` becomes, because `test` raised
    }
}
```

Both paths meet in one slot: when the call returns normally, the `try` is worth the call's own return
value and the extractor never runs; when the call `raise`s, the `try` is worth the extractor's
`yield`. That is why the expression form *requires* a `yield` — without one there would be no value to
give the reader on the `raise` path, and the analyzer says so rather than silently yielding `null`:

```
ExceptionSample.neko:5:9: error: this 'try' is used as a value, so its extractor must 'yield' the value
to use when the call 'raise's instead of returning
```

`yield` and `return` therefore mean different things inside an extractor, because the extractor's body
runs in a class of its own when the call `raise`s rather than in the enclosing function: `yield`
produces the `try`'s value, and `return` has no function to return from and is rejected.

The value-carrying form is lowered against a second extractor family, so the two shapes stay distinct:

```java
// r: int = try f() { a -> yield 1 }  — the extractor's invoke returns the value
EmitExtractorWithResult1<Integer, Integer> action = (a) -> 1;
int r;
try { r = f(); } catch (EarlyEmitException e) { r = (Integer) e.doEmit(action); }
```

Which family a `try` implements is decided by whether its extractor yields:
`EmitExtractorWithResult<N>` when it does, `EmitExtractor<N>` when it does not.

Three rules follow from the lowering and are all reported as errors:

- a function with an `emit` clause must actually `raise` somewhere — or forward a `raise` it received
  — or its results could never be received;
- `raise` is only legal inside such a function, and the number of values must match the clause;
- a `try` used as a value must `yield`, and a `try` statement must not.

A `raise` is a departure, so a statement after it can never run and is reported at **error** level —
as the sample's own `// Unreachable, notice as error level.` comment requires. The same applies after
`return`.

## Imports

`import` names a *package* whose classes a file may refer to by simple name:

```neko
locate com.example.app

import com.example.helpers

class Main {
    static fun main() {
        Helper.run()        // a class in com.example.helpers, compiled elsewhere
    }
}
```

An import can only ever **add** a way to resolve a name — it never hides a declaration. The program's
own classes are already known, so they resolve from anywhere; what an import changes is the package a
name the program does *not* declare belongs to. With no import that package is the file's own `locate`
package, which is the rule that was there before imports existed. Importing the same package twice in
one file is an error.

The standard library needs no import at all. `EarlyEmitException` and `EmitExtractorN` are Neko
sources compiled in the same run as the program, so they are declared like any other class and resolve
from any package:

```neko
locate com.example.app     // not the library's package, and no `import` either

class Main {
    static fun main() {
        value: int = try Emitter.test() { raised ->
            yield raised
        }
        println(value)
    }
}
```

## The standard library

`EarlyEmitException`, `EmitExtractor` and the extractor families `EmitExtractor0`…`EmitExtractor9`
and `EmitExtractorWithResult0`…`EmitExtractorWithResult9` are **written in Neko**, under
[`samples/library`](samples/library), and compiled in the same run as the programs:

```neko
locate com.github.kusa233.neko.stdlib.emit

public class EarlyEmitException: RuntimeException() {
    private val data: Object[]

    constructor(data: Object[]) {
        this.data = data
    }

    fun doEmit(extractor: EmitExtractor): Object {
        return extractor.extract(this.data)
    }
}
```

Each `EmitExtractorN` adds one abstract `invoke` of the arity's values; `extract` is a **default
method** that unpacks the `Object[]` and forwards each element — the array is unpacked in Neko
rather than in generated code, which keeps the compiler's lowering to a single method call per `try`
regardless of how many values were raised. The `N` family's `invoke` answers nothing and its `extract`
answers `null`, which the statement form discards; the `EmitExtractorWithResultN<R, T1…Tn>` family
answers `R`, which is what a `try` used as a value receives.

Because the library is a program like any other, it demonstrates the features the rest of the
language needs — `RuntimeException()` is a platform superclass, `private val` assigned in a
constructor, an interface with an abstract method and a default one, and classes with type parameters
whose bodies only forward values (so `T` erases to `Object`).

`raise` is not a JVM operation, so a program that uses it *must* include the library. Compiling one
without it is a compile error rather than a `NoClassDefFoundError` at load time:

```
ExceptionSample.neko:5:9: error: 'raise' needs the Neko standard library, but this program does not
include it: EarlyEmitException, EmitExtractor are not declared. Write them yourself, or compile the
library's sources together with this program — the build passes every directory in
'neko.libraryDirectories' to the compiler alongside the program
```

**Construction.** A capitalised name followed by arguments constructs: `CustomException()` emits
`NEW`/`INVOKESPECIAL`.

## Layout

| Path | Contents |
| --- | --- |
| `samples/com/github/kusa233/neko/sample` | The programs: `Sample.neko`, `ExceptionSample.neko` |
| `samples/library` | The standard library, written in Neko |
| `src/main/kotlin/.../lexer` | `Token`, `Lexer` — scanning, comments, string escapes |
| `src/main/kotlin/.../parser` | `Parser` — recursive descent with error recovery |
| `src/main/kotlin/.../ast` | The node types, each carrying its `Span` |
| `src/main/kotlin/.../analyzer` | Name resolution, type checking, `BuiltinLibrary` |
| `src/main/kotlin/.../codegen` | `BytecodeGenerator` — ASM, JVM class files |
| `src/main/kotlin/.../diagnostics` | `Diagnostic`, `DiagnosticBag`, `DiagnosticRenderer` |
| `src/main/kotlin/.../types` | The semantic type model |
| `src/main/kotlin/.../symbols` | Class/field/function/local symbols |
| `src/test/kotlin` | 168 tests: lexer, parser, diagnostics, types, language features, emit, imports, and both samples end to end |

## Tests

`./gradlew test` runs 168 tests. The suite is organised so each file answers one question:

- `LexerTest` — positions, escapes, comments, malformed input, the keyword set.
- `ParserTest` — tree shape, and that a mistake produces *useful* diagnostics rather than a cascade.
- `DiagnosticRendererTest` — the `file:line:column` header and that the caret sits under the exact
  offending text (checked by comparing against the column in the rendered source line, so it cannot
  pass by accident).
- `TypeCheckingTest` — name resolution, type errors, overload selection, capture rules.
- `InstanceMemberTest` — `static`, instance members, `obj.member`, and that neither is reachable the
  wrong way round.
- `LanguageFeatureTest` — inheritance, overriding, interfaces, constructors, `Object` boxing, array
  indexing, and that the standard library compiles on its own.
- `EmitTest` — `raise`/`emit`/`try`: the builder's name and descriptor, the declared exception, the
  capture rule for extractors, pass-through when the clauses match, and the programs actually running.
- `ImportTest` — `import` naming a package, the rule that applies without one, and that the standard
  library needs none.
- `SampleRulesTest` — the rules the samples' comments state, each test quoting the comment it
  enforces.
- `SampleEndToEndTest` — compiles the real `Sample.neko` and `ExceptionSample.neko` from disk, runs the
  generated bytecode, and asserts each prints exactly what its comments promise.

Diagnostic positions are asserted through a helper that locates the text in the fixture rather than
through literal line/column numbers, so editing a test's source cannot leave a stale expectation
behind — and a test that checks *where* the compiler pointed keeps working when the fixture moves.

## Known gaps

- Arrays exist as types (`string[]`, `int[]`, `Object[]`) and elements may be read and written by
  index, but there is no array *creation* expression: only the compiler's own `raise` builder makes
  one. Array literals are not implemented.
- A class header may carry `()`, but not constructor parameters; constructor arguments are declared
  with a `constructor(...)` body instead.
- A field initializer may not refer to another field of the same class, because the static ones all
  run in `<clinit>` in declaration order and a forward reference would silently read a default value.
- There is no `super(...)` call, so a superclass must have a no-argument constructor.
- A `raise` inside a `try` extractor body propagates out of the extractor rather than being caught by
  the `try` that created it.
- An `import` is not checked against the filesystem: a class the program does not declare is compiled
  against the package the import names, and whether anything is actually there is answered by the JVM
  when the class file is loaded. Only a name that could not be a class at all is rejected.
