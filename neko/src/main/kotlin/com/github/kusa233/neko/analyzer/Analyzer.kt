package com.github.kusa233.neko.analyzer

import com.github.kusa233.neko.ast.*
import com.github.kusa233.neko.diagnostics.DiagnosticBag
import com.github.kusa233.neko.platform.PlatformLibrary
import com.github.kusa233.neko.source.Span
import com.github.kusa233.neko.symbols.*
import com.github.kusa233.neko.types.*

/**
 * Name resolution and type checking — the pass that enforces Neko's rules and records, on every
 * AST node, everything the code generator needs.
 *
 * The rules exercised by `Sample.neko` are worth stating explicitly, because the sample's comments
 * are the specification:
 *
 *  * **A declaration declares.** `str: string = "..."` introduces a new variable, so a second
 *    `str: string = "new value"` *in the same scope* is an error — "variable 'str' is already
 *    declared in this scope", reported at the name, with a note at the previous declaration.
 *  * **An assignment assigns.** `str = "new value"` writes to the existing variable, so it compiles
 *    provided the variable is mutable. A local written without `val`/`var` is mutable; `val` is not.
 *  * **A local is not in scope inside its own initializer.** That is why
 *    `lambda: int = lambda()(1, 2)` resolves `lambda()` to the *function* named `lambda` rather than
 *    to the variable being declared.
 *  * **Lambdas take their parameter types from context**, which is why `{ x, y -> yield x + y }`
 *    needs no annotations when the function's declared return type is `[int, int] -> int`.
 *
 * Every diagnostic is anchored to the exact offending [Span]; where a message is easier to act on
 * with context, a `note` carries the secondary location.
 */
class Analyzer(private val program: NekoProgram, private val diagnostics: DiagnosticBag) {

    private val scopes = ArrayDeque<Scope>()
    /**
     * Every class the program declares, keyed by its **fully qualified** JVM name.
     *
     * The key used to be the simple name, which made a class name global: `demo.a.Helper` and
     * `demo.b.Helper` were the same entry, and the second declaration was refused as a duplicate of
     * the first. Nothing about the language ever said a simple name was unique across packages, so the
     * key is what `locate` plus the name actually denotes.
     */
    private val classes = LinkedHashMap<String, ClassSymbol>()

    /**
     * The declarations grouped by simple name, for the unqualified lookups.
     *
     * A file writes `Helper`, not `demo.a.Helper`, so resolution still has to start from a simple
     * name — and now it may find several candidates. Which of them the name denotes is decided by
     * [classBySimpleName], which applies the same package-and-import rule that decides whether a name
     * may be written at all.
     */
    private val classesBySimpleName = LinkedHashMap<String, MutableList<ClassSymbol>>()

    /**
     * The packages each class may reach by simple name, from the `import` clauses of its file.
     *
     * A name is resolved against this file's own package by default; an `import` is what adds
     * another package a name may come from. The standard library appears in no such list: it is
     * compiled as part of the program, so its classes are already declared and resolve from
     * anywhere without an import.
     */
    private val importPrefixes = LinkedHashMap<ClassSymbol, List<String>>()

    /**
     * The single-class imports of each file, by the simple name they pin.
     *
     * Keyed by class symbol like [importPrefixes], because a class' own `import` clauses are what
     * decide where *its* bodies may look — the same reason the package imports are stored that way.
     * Filled in after [checkImports] has decided which clauses name a class, since that decision is
     * what makes a clause appear here at all.
     */
    private val importedClasses = LinkedHashMap<ClassSymbol, Map<String, ImportDeclaration>>()

    /** Non-null while analysing the body of a lambda literal. */
    private var currentLambda: LambdaState? = null

    /**
     * The identity of the generated method the body being analysed will be compiled into, if any.
     *
     * A lambda and a `try` handler both become a method of their own, in a class of their own, so a
     * local declared inside one is *its* local and anything else it names has to be captured. Every
     * `LocalSymbol` gets stamped with this identity when it is created, and the code generator reads
     * the stamp back to tell the two apart: a symbol carrying the current identity is a slot of this
     * method, and one that does not is a field of the generated class.
     *
     * `null` while analysing an ordinary function body, where every local really is a local.
     *
     * This is separate from [currentLambda] rather than folded into it because the two answer
     * different questions: `currentLambda` is about lambda *semantics* — a `yield` returns from it, a
     * `this` is refused inside it — while this is only about where a declaration physically lives.
     * A handler body is a method boundary without being a lambda, and conflating them would make
     * `yield` mean the wrong thing inside a `try` block.
     */
    private var currentBoundaryIdentity: Any? = null

    /** Non-null while analysing the body of a declared function. */
    private var currentFunction: FunctionSymbol? = null

    /**
     * Non-null when a bare reference to an instance member may use implicit `this`.
     *
     * Set while analysing the body of a non-static function, and cleared inside a lambda: a lambda
     * body runs in a different object, so it has no `this` to reach the enclosing instance with.
     */
    private var implicitThisClass: ClassSymbol? = null

    /**
     * Non-null while analysing the body of a `try` extractor.
     *
     * The extractor body is emitted inside the generated extractor class, not in the enclosing
     * function, so the two statements that transfer control out of it — `yield` and `return` — mean
     * something different there and are checked against this.
     */
    private var currentExtractor: HandlerOwner? = null

    /**
     * Every `try` that declared a handler, in source order.
     *
     * Nothing is matched against this any more — a handler is a value a call carries, so a `scratch`
     * reads the one its own function was given — but the list is what the last declaration pass walks
     * to know a handler's value types without re-walking the tree.
     */
    private val handlers = mutableListOf<ExtractorTry>()

    /**
     * The locks held by the `purr` blocks currently open around the statement being analysed.
     *
     * A `StampedLock` is not reentrant, so taking one this thread already holds is a deadlock rather
     * than an error — which is why nesting has to be tracked here rather than left to run time. It is
     * a stack because the blocks nest, and it is cleared when a method body starts because a lambda's
     * body is a different method: a `purr` outside a lambda holds a lock the lambda's own body cannot
     * see, and the reverse.
     */
    private val heldPurrLocks = ArrayDeque<PurrLock>()

    /**
     * Non-null while analysing the two blocks of a `nya` that is used as a value.
     *
     * Only the value form sets this. In the two forms that are worth nothing — bare, and with a lone
     * failure block — a `yield` inside the block still means what it means outside it, so a lambda
     * wrapping `nya a == b { yield 1 }` keeps its own result; here it would be a second, invisible
     * home for the same word. Both blocks of a value form are analysed under one of these, which is
     * what lets the first `yield` in *either* of them fix the type the other must match: the two are
     * two answers to one question, so they are worth the same type or the construct means nothing.
     */
    private var currentNya: NyaValue? = null

    /**
     * The `nya` in value position whose blocks are being analysed, and the type they are being asked
     * for.
     *
     * [expected] is the type the construct was used where — `v: int = ...` — and is a hint the first
     * `yield` may be inferred from rather than a requirement, exactly as it is for a lambda. The type
     * actually produced is recorded on the node itself, because that is what code generation reads.
     *
     * [inFailureBlock] is what tells the two blocks' `yield`s apart, since both belong to this one
     * `nya` and the difference only matters when reporting which of them never produced anything.
     */
    private class NyaValue(val expression: NyaExpression, val expected: NekoType?) {
        var inFailureBlock = true
        var failureYielded = false
        var elseYielded = false
    }

    /**
     * What the tests passed so far have proved, keyed by the declaration each fact is about.
     *
     * A `T?` is a value that may be absent, and every read of one has to be guarded. Writing the guard
     * and then reading anyway is the most ordinary thing a reader does, so a test that settles the
     * question for a region of code — `if (x != null) { x.f }` — is remembered here and the read
     * inside that region is typed `T` rather than `T?`. This is a *narrowing*: it is a statement about
     * this path through the program, not about the declaration, which is why the map is saved and
     * restored around every construct that forks the flow, and why a write to the declaration drops
     * its fact with [forgetNarrowingsOf]. Restoring is not simply putting the map back: a write inside
     * an arm *may* have run, so [saveNarrowings] also records how many writes each key had seen and
     * [restoreNarrowings] refuses to revive a fact that has been written since.
     *
     * A key is a declaration rather than a name, because a name can be shadowed and only the symbol
     * says which value a test is about. That leaves three shapes, built by [narrowingKeyOf]: a
     * [LocalSymbol], a [FieldSymbol] declared with `val`, and a [FieldFact] naming such a field *of*
     * such a value — which is what lets `if (point.x != null) { point.x }` work without trusting
     * `point` itself.
     *
     * A `var` *local* is a key even though it can be reassigned, because nothing outside its function
     * can write it: dropping the fact at every write the analyzer reaches is enough on its own, and
     * that is what lets a *parameter* be narrowed at all — every parameter reports itself mutable.
     * A `var` *field* is not a key, because any other method could write it between the test and the
     * read and the analyzer would never see the write.
     *
     * Facts are found by [factsTrue] and [factsFalse], which read a condition into what its two
     * outcomes prove, and are consulted by the places that type a read of a stored value: the bare
     * name in [computeType], the field read in [analyzeMemberAccess] and [staticMemberOf], and the
     * callee of a call in [analyzeCall]. A fact about a local is about the value the lambda would
     * capture, so a lambda body keeps the ones about *names* and drops the ones about fields, whose
     * value it will read whenever it is called rather than when it is written.
     */
    private val narrowings = LinkedHashMap<Any, NekoType>()

    /**
     * `true` while the callee of a `try … { … -> }` is being resolved.
     *
     * The call a `try` wraps is the one call to an `emit`-declaring function that is legal, so the
     * check that rejects such a call everywhere else has to know it is looking at that one.
     */
    private var resolvingTryCallee = false

    /**
     * The class whose members are being resolved or analysed right now.
     *
     * Used to resolve a type parameter name — `T` in `class Box<T>` — to the `Object` the JVM erases
     * it to, and to know which class a bare instance member belongs to.
     */
    private var currentClass: ClassSymbol? = null

    /**
     * The type parameters of the generic function whose signature or body is being analysed.
     *
     * Empty everywhere else. `resolveType` consults this together with [ClassSymbol.typeParameters],
     * because `T` in `fun <T> f(value: T)` means the function's own parameter rather than any name
     * the enclosing class happened to declare — and a name that is neither is still an ordinary type
     * that has to resolve normally.
     */
    private var currentTypeParameters: List<String> = emptyList()

    /**
     * Whether the "is `EmitYieldException` loadable?" diagnostic has already been considered.
     *
     * It is a property of the program rather than of any one `try`, so it is reported once however
     * many `try` statements appear: repeating it on every one would bury the rest of the output.
     */
    private var standardLibraryChecked = false

    /** Parameter symbols, keyed by declaration, so code generation and analysis agree on order. */
    private val parametersByFunction = LinkedHashMap<FunctionDeclaration, List<LocalSymbol>>()

    /**
     * Whether the function currently being checked forwards its `emit` clause to its own caller.
     *
     * Set when a call passes the clause on unchanged, which is a second way a function with an
     * `emit` clause can do work with it — beside reaching a handler directly with `scratch`. The
     * "declares an 'emit' clause but can never reach a handler" check consults it.
     */
    private var forwardsRaise = false

    /** Built-ins are consulted first, so a program can always reach `println`. */
    private val builtins: Map<String, List<FunctionSymbol>> = BuiltinLibrary.functions()

    /** Analyses the file and returns the class symbols the code generator needs. */
    fun analyze(): Map<String, ClassSymbol> {
        checkImports()
        declareClasses()
        resolveSignatures()
        checkBodies()
        // Nothing has to be linked afterwards any more. A `scratch` used to be paired with a `try`
        // here, by matching emit signatures once the whole program was known — which meant one
        // handler per signature, and a program whose behaviour depended on what else was declared.
        // A handler is now a value the call carries, so each site reads the handler in its own
        // function's parameter and every `try` is independent. See [analyzeScratch].
        return classes
    }

    /**
     * Reports an `import` clause repeated in the same file.
     *
     * A second `import` of the same package adds nothing — resolution already consults a package once
     * — so it is a mistake in the source rather than a second chance to find a name, and saying so
     * here keeps the resolution order a plain reading of the file's own clauses.
     */
    private fun checkImports() {
        for (file in program.files) {
            val seen = mutableMapOf<String, ImportDeclaration>()
            for (declaration in file.imports) {
                // Whether this clause names a class or a package is decided here, once, by asking the
                // platform — and it is decided *before* the duplicate check below, because `import
                // java.util` and `import java.util.HashMap` are not duplicates of each other even
                // though one name is a prefix of the other.
                classifyImport(declaration)

                val previous = seen.put(declaration.name, declaration)
                if (previous == null) continue
                diagnostics.error(
                    "'${declaration.name}' is already imported",
                    declaration.span,
                    notes = listOf(
                        diagnostics.note("previous import of '${declaration.name}' is here", previous.span),
                    ),
                )
            }
        }
    }

    /**
     * Decides whether [declaration] imports one class or a whole package, in place.
     *
     * `import java.util.HashMap` and `import java.util` are the same syntax and the same shape; the
     * only difference is what the name *is*. So the question is put to the platform: if the whole
     * dotted name is a class the JVM can load, it is a class import, and otherwise it is a package.
     * That is what the requirement "when the target is not a class, import the whole package" means
     * operationally, and it is also why a package the platform does not describe still works — an
     * unknown name is simply not a platform class, so it is taken as a package, which is the same
     * treatment such a package has always had.
     *
     * The program's own classes are asked about first. A file may import the package of a class it
     * compiles itself, and `import com.example.Helper` should then mean `Helper` rather than a package
     * called `com.example.Helper`; the program's declarations are the only other authority that knows
     * a class by its full name.
     */
    private fun classifyImport(declaration: ImportDeclaration) {
        val owner = declaration.name
        // A dotted name whose last segment is not capitalized cannot be a class, and asking the
        // platform about it would only cost a classload attempt per import on every build.
        val simpleName = owner.substringAfterLast('.')
        if (simpleName == owner || simpleName.firstOrNull()?.isUpperCase() != true) return

        // The program's own declarations are asked first, and read from the AST rather than from
        // `classes`: imports are classified before the class symbols exist, because the symbols'
        // resolution order is itself built from these clauses. A file may legitimately import the
        // package of a class it compiles itself, and the declaration says which package that is.
        val declared = program.classes.firstOrNull { it.name == simpleName }
        if (declared != null) {
            val packageName = program.fileOf(declared)
                ?.packageDeclaration?.name
                .orEmpty()
            declaration.isClassImport = packageName == owner.substringBeforeLast('.', "")
            return
        }

        declaration.isClassImport = loadQualifiedClass(owner) != null
    }

    /** Parameter symbols for [function]; used by the code generator to place locals. */
    fun parametersOf(function: FunctionDeclaration): List<LocalSymbol> =
        parametersByFunction[function].orEmpty()

    /** All parameter symbols, for the code generator. */
    fun allParameters(): Map<FunctionDeclaration, List<LocalSymbol>> = parametersByFunction

    // -------------------------------------------------------------- declaration

    private fun declareClasses() {
        for (declaration in program.classes) {
            // `meow` derives a class' `equals` and `hashCode` from its fields, and an interface has
            // neither state nor an implementation to derive them into. Reported here, before the
            // symbol is built, because the declaration is not a class at all — nothing later could
            // make sense of it, and a second error from a half-built symbol would only be noise.
            if (declaration.isMeow && declaration.isInterface) {
                diagnostics.error(
                    "'meow' cannot declare an interface: an interface has no fields to derive " +
                        "'equals' and 'hashCode' from, so write 'interface ${declaration.name} { }'",
                    declaration.nameSpan,
                )
                continue
            }

            // An enum's constants are instances of the enum itself, so an interface — which is never
            // instantiated — cannot be one. Reported here for the same reason as the `meow` case: the
            // symbol below would be a class that cannot exist.
            if (declaration.isMiao && declaration.isInterface) {
                diagnostics.error(
                    "'miao' cannot declare an interface: an enum's constants are instances of the " +
                        "enum, and an interface has none to create; write 'interface " +
                        "${declaration.name} { }'",
                    declaration.nameSpan,
                )
                continue
            }

            val packagePrefix = program.packagePrefixOf(declaration)
            val internalName = packagePrefix + declaration.name
            val previous = classes[internalName]
            if (previous != null) {
                diagnostics.error(
                    "${if (declaration.isInterface) "interface" else "class"} " +
                        "'${declaration.name}' is already declared",
                    declaration.nameSpan,
                    notes = listOf(
                        diagnostics.note(
                            "previous declaration of '${declaration.name}' is here",
                            previous.span,
                        ),
                    ),
                )
                continue
            }
            val symbol = ClassSymbol(
                name = declaration.name,
                span = declaration.nameSpan,
                jvmInternalName = internalName,
                packagePrefix = packagePrefix,
                isInterface = declaration.isInterface,
                isMeow = declaration.isMeow,
                isMiao = declaration.isMiao,
                typeParameters = declaration.typeParameters,
            )
            classes[internalName] = symbol
            classesBySimpleName.getOrPut(declaration.name) { mutableListOf() } += symbol
            declaration.symbol = symbol
            // Resolution reads the declaring file's clauses rather than a per-file flag, because a
            // class' own `import` clauses are what decide where *its* bodies may look for a name.
            val file = program.fileOf(declaration)
            importPrefixes[symbol] = file?.importPrefixes().orEmpty()
            // Only the clauses `checkImports` recognised as naming a class; the rest are packages and
            // are already covered by `importPrefixes`.
            importedClasses[symbol] = file?.importedClasses().orEmpty()
        }
    }

    private fun resolveSignatures() {
        for (declaration in program.classes) {
            val classSymbol = declaration.symbol ?: continue
            declaration.symbol = classSymbol
            currentClass = classSymbol
            resolveSupertypes(declaration, classSymbol)
            currentClass = null
        }
        // Local variable scopes for type parameters need the supertypes resolved first, so a
        // supertype's own type parameters are known before a member mentions one.
        for (declaration in program.classes) {
            val classSymbol = declaration.symbol ?: continue
            currentClass = classSymbol
            declareFields(declaration, classSymbol)
            if (declaration.isMiao) declareEnumEntries(declaration, classSymbol)
            declareFunctions(declaration, classSymbol)
            declareConstructors(declaration, classSymbol)
            currentClass = null
        }
        // Every class' constructors are declared now, so a subclass can be checked against a
        // superclass declared later in the file — or in another file.
        checkSuperclassesAreConstructible()
    }

    /**
     * Resolves the supertypes named in a class header.
     *
     * A superclass and an interface both become [ClassType]s, and the analyzer records the symbol
     * behind each when it is declared in this file — which is what lets member lookup walk up the
     * hierarchy.
     */
    private fun resolveSupertypes(declaration: ClassDeclaration, classSymbol: ClassSymbol) {
        // An enum always extends `java.lang.Enum`, which is what gives it `name()`, `ordinal()`,
        // `compareTo` and the `values`-friendly behaviour the JVM expects of `ACC_ENUM`. The reader
        // never writes it — `miao class` is the whole declaration — so it is recorded here, exactly
        // as `Object` is implicitly the superclass of a class that names none.
        //
        // A named superclass is refused rather than merged: `Enum` is one class, and a second one
        // would leave the enum's `name()` and its constants' identity answering different questions.
        if (declaration.isMiao) {
            if (declaration.superclass != null) {
                diagnostics.error(
                    "enum '${declaration.name}' cannot extend '${declaration.superclass.name}': every " +
                        "enum extends java.lang.Enum, which is what makes its constants comparable " +
                        "and nameable",
                    declaration.superclass.nameSpan,
                )
            }
            val enumType = ClassType("java/lang/Enum", "Enum")
            classSymbol.superclass = enumType
            // Left null: `java.lang.Enum` is not declared in this compilation, so there is no member
            // table to walk. Its members are found through the platform instead.
            classSymbol.superclassSymbol = null
        }

        val superclass = declaration.superclass
        if (superclass != null && !declaration.isMiao) {
            val type = resolveType(
                NamedTypeReference(superclass.name, superclass.nameSpan, superclass.typeArguments, superclass.span),
            )
            superclass.resolvedType = type
            if (type is ClassType) {
                classSymbol.superclass = type
                val parent = classes[type.internalName]
                classSymbol.superclassSymbol = parent
                if (parent != null && parent.isInterface) {
                    diagnostics.error(
                        "'${superclass.name}' is an interface, so it cannot be extended with " +
                            "'${superclass.name}()'; list it directly instead",
                        superclass.nameSpan,
                    )
                }
            }
        }

        for (implemented in declaration.interfaces) {
            val type = resolveType(
                NamedTypeReference(implemented.name, implemented.nameSpan, implemented.typeArguments, implemented.span),
            )
            implemented.resolvedType = type
            if (type is ClassType) {
                classSymbol.interfaces += type
                classes[type.internalName]?.let { classSymbol.interfaceSymbols += it }
            }
        }

        if (classSymbol.superclassSymbol != null) {
            checkInheritanceCycle(classSymbol, declaration)
        }
    }

    /**
     * Requires a superclass to be constructible with no arguments.
     *
     * Neko has no `super(...)` syntax, so every subclass constructor starts by calling the
     * superclass' no-argument constructor — the only one it can name. A superclass that needs
     * arguments could never be initialized, so the subclass is rejected rather than compiled into a
     * class the verifier refuses.
     *
     * This runs after every class' constructors are declared, because the answer depends on the
     * superclass' *declaration*, which may come later in the file or in another file entirely.
     */
    private fun checkSuperclassesAreConstructible() {
        for (declaration in program.classes) {
            val classSymbol = declaration.symbol ?: continue
            val superclass = declaration.superclass ?: continue
            val parent = classSymbol.superclassSymbol ?: continue
            if (parent.constructors.isEmpty()) continue
            if (parent.constructors[0] != null) continue

            diagnostics.error(
                "'${parent.name}' has no constructor taking no arguments, and Neko has no " +
                    "'super(...)' syntax; give '${parent.name}' a constructor that takes none",
                superclass.nameSpan,
            )
        }
    }

    /** Rejects `class A: B` / `class B: A`, which would make member lookup non-terminating. */
    private fun checkInheritanceCycle(classSymbol: ClassSymbol, declaration: ClassDeclaration) {
        var current: ClassSymbol? = classSymbol.superclassSymbol
        while (current != null) {
            if (current === classSymbol) {
                diagnostics.error(
                    "class '${declaration.name}' cannot extend itself, directly or through " +
                        "another class",
                    declaration.nameSpan,
                )
                classSymbol.superclass = null
                classSymbol.superclassSymbol = null
                return
            }
            current = current.superclassSymbol
        }
    }

    private fun declareFields(declaration: ClassDeclaration, classSymbol: ClassSymbol) {
        for (field in declaration.fields) {
            val type = resolveType(field.declaredType)
            field.resolvedType = type

            val existing = classSymbol.fields[field.name]
            if (existing != null) {
                diagnostics.error(
                    "field '${field.name}' is already declared in class '${declaration.name}'",
                    field.nameSpan,
                    notes = listOf(
                        diagnostics.note("previous declaration of '${field.name}' is here", existing.span),
                    ),
                )
                continue
            }

            val symbol = FieldSymbol(
                name = field.name,
                type = type,
                span = field.nameSpan,
                kind = field.kind,
                modifier = field.modifiers.firstOrNull()?.kind?.display,
                enclosingClass = classSymbol,
                isStatic = field.isStatic,
            )
            classSymbol.fields[field.name] = symbol
            field.symbol = symbol
        }
    }

    /**
     * Declares the constants of a `miao class` as `static final` fields of the enum's own type.
     *
     * Nothing new is invented for them: an entry becomes an ordinary field symbol, so `Color.RED`
     * resolves through the member lookup that already exists, and code generation reads it with the
     * `GETSTATIC` it already emits. What is new is only where the field's *value* comes from — the
     * compiler writes it in `<clinit>` — and that is code generation's business.
     *
     * The header fields are declared before this runs, which is what lets the duplicate check below
     * catch `miao class C(val x: int) { x }`: the constant would need the same field name as the
     * parameter it fills.
     */
    private fun declareEnumEntries(declaration: ClassDeclaration, classSymbol: ClassSymbol) {
        if (declaration.entries.isEmpty()) {
            diagnostics.error(
                "'${declaration.name}' is an enum, so it must name at least one constant; write " +
                    "'miao class ${declaration.name} { FIRST }'",
                declaration.nameSpan,
            )
            return
        }

        val enumType = ClassType(classSymbol.jvmInternalName, classSymbol.name)
        // The parameter types the constants are called with, which is the header's fields in order.
        // Every constant must supply them, because they are one constructor's parameters.
        val parameterTypes = declaration.fields
            .filterNot { it.isStatic }
            .map { it.resolvedType ?: ErrorType }

        for (entry in declaration.entries) {
            val existing = classSymbol.fields[entry.name]
            if (existing != null) {
                diagnostics.error(
                    "constant '${entry.name}' is already declared in enum '${declaration.name}'",
                    entry.nameSpan,
                    notes = listOf(
                        diagnostics.note(
                            "previous declaration of '${entry.name}' is here",
                            existing.span,
                        ),
                    ),
                )
                continue
            }

            // The constant is an instance of its own enum, so its type is the enum itself — which is
            // what makes `Color.RED` assignable to a `Color` and nothing else.
            val symbol = FieldSymbol(
                name = entry.name,
                type = enumType,
                span = entry.nameSpan,
                kind = VariableKind.VAL,
                modifier = "public",
                enclosingClass = classSymbol,
                isStatic = true,
            )
            classSymbol.fields[entry.name] = symbol
            entry.symbol = symbol

            // The arguments are analyzed in the enum's own scope, after all of its fields exist, so a
            // constant may be built from a `static` field: `miao class Code { OK(MIN) }`. A scope has
            // to be pushed for that, because a bare name resolves through the scope stack rather than
            // through `currentClass` — this runs during signature resolution, where no class scope is
            // open yet.
            val enclosingClass = currentClass
            currentClass = classSymbol
            pushScope(identifier = classSymbol)
            for (field in classSymbol.fields.values) scopes.last().symbols[field.name] = field
            val analyzed = entry.arguments.map { analyzeExpression(it) }
            popScope()
            currentClass = enclosingClass
            if (analyzed.size != parameterTypes.size) {
                diagnostics.error(
                    "constant '${entry.name}' takes ${parameterTypes.size} " +
                        "${if (parameterTypes.size == 1) "argument" else "arguments"} but was given " +
                        analyzed.size,
                    entry.span,
                )
                continue
            }
            for ((index, actual) in analyzed.withIndex()) {
                val expected = parameterTypes[index]
                if (!isAssignable(actual, expected)) {
                    diagnostics.error(
                        "argument ${index + 1} of constant '${entry.name}' is " +
                            "'${actual.displayName}' but '${expected.displayName}' was expected",
                        entry.arguments[index].span,
                    )
                }
            }
        }
    }

    private fun declareFunctions(declaration: ClassDeclaration, classSymbol: ClassSymbol) {
        for (function in declaration.functions) {
            // A generic function's own names are visible in its signature and its body, and only
            // there. Setting them at the head of the iteration is what makes `fun <T> f(value: T)`
            // resolve `T` without a second notion of "what is in scope" reaching `resolveType`.
            // Every path through the body sets it before reading it, so an early `continue` cannot
            // leak one function's names into the next.
            currentTypeParameters = function.typeParameters
            val parameterTypes = function.parameters.map { resolveType(it.declaredType) }
            val returnType = function.returnType?.let { resolveType(it) } ?: VoidType
            function.resolvedParameterTypes = parameterTypes
            function.resolvedReturnType = returnType
            function.resolvedEmitTypes = function.emitTypes.map { resolveType(it) }
            function.resolvedEmitResultType =
                if (function.hasEmitClause) resolveEmitResultType(function.emitResultType) else null

            val overloads = classSymbol.functions.getOrPut(function.name) { mutableListOf() }
            val clash = overloads.firstOrNull { it.parameterTypes == parameterTypes }
            if (clash != null) {
                diagnostics.error(
                    "function '${function.name}' with parameter types " +
                        "(${parameterTypes.joinToString(", ") { it.displayName }}) is already declared",
                    function.nameSpan,
                    notes = listOf(diagnostics.note("previous declaration is here", clash.span)),
                )
                continue
            }

            checkEmitDeclaration(function, returnType)

            // `java.lang.Enum` declares `name()` and `ordinal()` final, so an enum that wrote its own
            // would produce a class file the JVM refuses to load — `VerifyError: class overrides final
            // method`. Rejecting it here turns a crash at run time into a message at the declaration,
            // which is also what keeps the constant's name and position authoritative.
            if (declaration.isMiao && function.name in ENUM_FINAL_METHODS && !function.isStatic) {
                diagnostics.error(
                    "enum '${declaration.name}' cannot declare '${function.name}': java.lang.Enum " +
                        "declares it final, because a constant's name and position are what the enum " +
                        "is",
                    function.nameSpan,
                )
            }

            if (function.isAbstract && !declaration.isInterface) {
                diagnostics.error(
                    "function '${function.name}' has no body: only a function declared in an " +
                        "interface may omit one, because a class must say what its functions do",
                    function.nameSpan,
                )
            }
            if (function.isAbstract && function.hasEmitClause) {
                diagnostics.error(
                    "abstract function '${function.name}' cannot declare an 'emit' clause: a " +
                        "function with no body has no 'scratch' to reach a handler with",
                    function.nameSpan,
                )
            }

            val symbol = FunctionSymbol(
                name = function.name,
                span = function.nameSpan,
                parameterTypes = parameterTypes,
                returnType = returnType,
                enclosingClass = classSymbol,
                declaration = function,
                isStatic = function.isStatic,
                emitTypes = function.resolvedEmitTypes,
                emitResultType = function.resolvedEmitResultType,
                hasEmitClause = function.hasEmitClause,
                handlerType = if (function.hasEmitClause) {
                    scratchHandlerTypeFor(function.resolvedEmitTypes.size, function.nameSpan)
                } else {
                    null
                },
                isAbstract = function.isAbstract,
                typeParameters = function.typeParameters,
            )
            overloads += symbol
            function.symbol = symbol
            parametersByFunction[function] = function.parameters.mapIndexed { index, parameter ->
                LocalSymbol(
                    name = parameter.name,
                    type = parameterTypes[index],
                    span = parameter.nameSpan,
                    kind = VariableKind.VAL,
                    isParameter = true,
                )
            }
        }

        // The loop above left the last function's names installed; a class's other declarations are
        // not generic, so they must not leak into what is resolved next.
        currentTypeParameters = emptyList()

        if (declaration.isMeow) declareDerivedFunctions(declaration, classSymbol)
        if (declaration.isMiao) declareEnumFunctions(declaration, classSymbol)
    }

    /**
     * The standard library's handler interface for an `emit` clause of [arity] values.
     *
     * `emit [int, int]` needs `ScratchHandler2`, because the interface is what the JVM knows how to
     * pass: it declares `dispatch(Object, Object): Object`, so the values travel boxed and the
     * answer comes back boxed. Which interface a clause needs is therefore decided by its arity
     * alone, and the argument types are checked by the analyzer rather than by the interface — the
     * library's interfaces are all `Object` so that one family serves every program.
     *
     * `null` when the library provides no such interface, which is either a clause longer than the
     * family covers or a program compiled without the library. Both are reported here rather than
     * left for the code generator, which knows no better.
     */
    private fun scratchHandlerTypeFor(arity: Int, span: Span): NekoType? {
        // Looked up by name but *not* through the import rule: the library is the compiler's own
        // machinery, so `scratch` reaches it from any package with no import written. That is the same
        // exemption `scratch`, `nyan` and `yield` have always had.
        val symbol = classesBySimpleName["ScratchHandler$arity"]?.firstOrNull()
        if (symbol != null) return ClassType(symbol.jvmInternalName, symbol.name)

        if (arity > maxEmitValues) {
            diagnostics.error(
                "an 'emit' clause may declare at most ${maxEmitValues + 1} values, but this one " +
                    "declares $arity: the standard library provides ScratchHandler0 to " +
                    "ScratchHandler$maxEmitValues, one per arity",
                span,
            )
        } else {
            diagnostics.error(
                "the standard library declares no 'ScratchHandler$arity', which this 'emit' clause " +
                    "needs to carry its values to a 'scratch'",
                span,
            )
        }
        return null
    }

    /** The highest `ScratchHandlerN` the standard library provides, so `emit` may hold one more. */
    private val maxEmitValues = 7

    /**
     * Declares the `equals`, `hashCode` and `toString` a `meow class` gets for free.
     *
     * Neko cannot express any of the three — `equals` has to compare a value whose type it does not
     * know, and none has a body the language could write — so the code generator emits them. What
     * the analyzer contributes is the *signature*: without a symbol here, `a.equals(b)` would be
     * "Neko has no member named 'equals'" even though the class file has the method, and the
     * generated trio would be unreachable from source.
     *
     * A hand-written `equals`, `hashCode` or `toString` wins. This runs after every declared member
     * is in place, so a body the author wrote is already known and the derived one is simply not
     * added; the functions are keyed by name and parameter types, so "the same signature" means a
     * real override rather than a second overload.
     */
    private fun declareDerivedFunctions(declaration: ClassDeclaration, classSymbol: ClassSymbol) {
        // A `meow interface` has already been reported and has no symbol to attach functions to: the
        // declaration is not a class, so there is nothing to derive from and nowhere to put it.
        if (declaration.isInterface) return

        val derived = listOf(
            FunctionSymbol(
                name = "equals",
                span = declaration.nameSpan,
                parameterTypes = listOf(ObjectType),
                returnType = BoolType,
                enclosingClass = classSymbol,
                // `declaration = null` is how a function with no Neko body is modelled; a
                // constructor has none either, and for the same reason — there is nothing to emit
                // from, so the body lives in the class file rather than in the tree.
                declaration = null,
                isStatic = false,
            ),
            FunctionSymbol(
                name = "hashCode",
                span = declaration.nameSpan,
                parameterTypes = emptyList(),
                returnType = IntType,
                enclosingClass = classSymbol,
                declaration = null,
                isStatic = false,
            ),
            FunctionSymbol(
                name = "toString",
                span = declaration.nameSpan,
                parameterTypes = emptyList(),
                returnType = StringType,
                enclosingClass = classSymbol,
                declaration = null,
                isStatic = false,
            ),
        ).filterNot { symbol ->
            classSymbol.functions[symbol.name].orEmpty().any { it.parameterTypes == symbol.parameterTypes }
        }

        for (symbol in derived) {
            classSymbol.functions.getOrPut(symbol.name) { mutableListOf() } += symbol
        }
    }

    /**
     * Declares `values()` and `valueOf(string)` on a `miao class`.
     *
     * Both are what `java.lang.Enum` gives a Java enum, and both are emitted as bytecode for the same
     * reason the `meow` trio is: neither has a body Neko could write. `values()` has to answer an
     * array of a type the source never names, and `valueOf` has to compare a `string` against every
     * constant and throw on a miss.
     *
     * Only the *signatures* are contributed here. Without them, `Color.values()` would be "Neko has
     * no member named 'values'" even though the class file has the method.
     *
     * `name()`, `ordinal()` and `compareTo` are declared here too, even though the JVM inherits them
     * from `java.lang.Enum`. Member lookup reads the symbol table of a class declared in this
     * compilation, and `Enum` is not — see [classOf] — so without a signature `Color.RED.name()`
     * would be "Color has no member named 'name'". They are not emitted: the inherited methods
     * already do exactly the right thing, and the constants are real `Enum` instances.
     */
    private fun declareEnumFunctions(declaration: ClassDeclaration, classSymbol: ClassSymbol) {
        val enumType = ClassType(classSymbol.jvmInternalName, classSymbol.name)

        val derived = listOf(
            FunctionSymbol(
                name = "values",
                span = declaration.nameSpan,
                parameterTypes = emptyList(),
                returnType = ArrayType(enumType),
                enclosingClass = classSymbol,
                // `declaration = null` marks a function the compiler emits rather than one the
                // reader wrote: there is no Neko body to generate from.
                declaration = null,
                isStatic = true,
            ),
            FunctionSymbol(
                name = "valueOf",
                span = declaration.nameSpan,
                parameterTypes = listOf(StringType),
                returnType = enumType,
                enclosingClass = classSymbol,
                declaration = null,
                isStatic = true,
            ),
            // Inherited rather than emitted: `Enum` already stores the name and the position, and
            // the constants are built with them in `<clinit>`. `inheritedFrom` is what makes the call
            // name `java/lang/Enum` rather than this enum, in whose class file the method does not
            // exist.
            FunctionSymbol(
                name = "name",
                span = declaration.nameSpan,
                parameterTypes = emptyList(),
                returnType = StringType,
                enclosingClass = classSymbol,
                declaration = null,
                isStatic = false,
                inheritedFrom = "java/lang/Enum",
            ),
            FunctionSymbol(
                name = "ordinal",
                span = declaration.nameSpan,
                parameterTypes = emptyList(),
                returnType = IntType,
                enclosingClass = classSymbol,
                declaration = null,
                isStatic = false,
                inheritedFrom = "java/lang/Enum",
            ),
            FunctionSymbol(
                name = "compareTo",
                span = declaration.nameSpan,
                // `Enum.compareTo` takes an `Enum`, not this enum: a `Color` argument is accepted
                // because it *is* one, and the erasure is what the class file actually declares.
                parameterTypes = listOf(ClassType("java/lang/Enum", "Enum")),
                returnType = IntType,
                enclosingClass = classSymbol,
                declaration = null,
                isStatic = false,
                inheritedFrom = "java/lang/Enum",
            ),
        ).filterNot { symbol ->
            classSymbol.functions[symbol.name].orEmpty().any { it.parameterTypes == symbol.parameterTypes }
        }

        for (symbol in derived) {
            classSymbol.functions.getOrPut(symbol.name) { mutableListOf() } += symbol
        }
    }

    /**
     * Declares a class' constructors, keyed by parameter count.
     *
     * A class with no constructor written still gets one, so `Cls()` always works: the code generator
     * emits a default constructor when the declaration list is empty, matching what the JVM does for
     * a class that declares none.
     */
    private fun declareConstructors(declaration: ClassDeclaration, classSymbol: ClassSymbol) {
        if (declaration.isInterface && declaration.constructors.isNotEmpty()) {
            diagnostics.error(
                "interface '${declaration.name}' cannot declare a constructor: an interface is " +
                    "never instantiated",
                declaration.constructors.first().span,
            )
        }

        // An enum's only constructor is the one the compiler emits, taking the header's fields and
        // handing them to `Enum` along with the constant's name and position. A constructor written
        // in the body would be a second way to build a constant — which is the one thing an enum
        // exists to rule out — and it could not call `Enum.<init>` anyway, since Neko has no
        // `super(...)` syntax.
        if (declaration.isMiao && declaration.constructors.isNotEmpty()) {
            diagnostics.error(
                "enum '${declaration.name}' cannot declare a constructor: an enum's constants are the " +
                    "only instances it has, and the compiler creates them from the constants listed " +
                    "in the declaration",
                declaration.constructors.first().span,
            )
        }

        for (constructor in declaration.constructors) {
            val parameterTypes = constructor.parameters.map { resolveType(it.declaredType) }
            constructor.symbol = FunctionSymbol(
                name = "<init>",
                span = constructor.span,
                parameterTypes = parameterTypes,
                returnType = VoidType,
                enclosingClass = classSymbol,
                declaration = null,
                isStatic = false,
                constructor = constructor,
            )
            classSymbol.constructors[parameterTypes.size] = constructor.symbol!!
        }
    }

    /**
     * Checks the shape of an `emit` clause against the function's declared return type.
     *
     * A function with an `emit` clause has *two* ways to leave, and they are not alternatives: it may
     * `scratch`, which hands values to the handler its caller's `try` declared and carries on with the
     * answer, or it may `return` normally. Declaring a return type as well is therefore meaningful —
     * it is the value the call produces whenever the function does not scratch, which is what
     * `r = try f() { ... }` reads. Only the arity of the clause is checked here; the result half is
     * resolved by [resolveEmitResultType], which answers [VoidType] for a clause that named none.
     */
    private fun checkEmitDeclaration(function: FunctionDeclaration, returnType: NekoType) {
        if (!function.hasEmitClause) return

        if (function.resolvedEmitTypes.size > MAX_EMIT_VALUES) {
            diagnostics.error(
                "an 'emit' clause may name at most $MAX_EMIT_VALUES values but this one names " +
                    "${function.resolvedEmitTypes.size}",
                function.emitTypes[MAX_EMIT_VALUES].span,
            )
        }
    }

    /**
     * The result an `emit` clause promises: the type it wrote after `->`, or [VoidType].
     *
     * A clause that names no result promises nothing, and that is a complete clause rather than half
     * of one: the handler simply answers "I am done" instead of a value. `emit [int]` and
     * `emit [int] -> void` therefore mean the same thing —the second is accepted so a clause whose
     * handlers answer nothing can say so out loud next to the ones that answer.
     *
     * `void` is not a nullable or assignable type, and it is deliberately not in [BuiltinTypes.byName]:
     * a local or a parameter of type `void` is meaningless, so the name is accepted in this position
     * only. Intercepting it here is what keeps `x: void = ...` an error while `-> void` parses.
     */
    private fun resolveEmitResultType(reference: TypeReference?): NekoType {
        if (reference == null) return VoidType
        if (reference is NamedTypeReference && reference.name == VOID_TYPE_NAME) return VoidType
        return resolveType(reference)
    }

    // ------------------------------------------------------------------ bodies

    private fun checkBodies() {
        for (declaration in program.classes) {
            val classSymbol = declaration.symbol ?: continue
            currentClass = classSymbol
            checkInterfacesImplemented(declaration, classSymbol)
            for (field in declaration.fields) checkFieldInitializer(field)
            for (function in declaration.functions) {
                // Recorded before the body is walked, so a function can be looked up again once every
                // body has been seen — which is what the `emit`-clause check at the end needs.
                function.symbol?.let { functionsByName[it.name] = it }
                checkFunctionBody(function, classSymbol)
            }
            for (constructor in declaration.constructors) {
                checkConstructorBody(constructor, declaration, classSymbol)
            }
            currentClass = null
        }
    }

    /**
     * Requires a class to provide every abstract method its interfaces declare.
     *
     * A method with a body in an interface is a default, so it is already provided; only an abstract
     * one has to be written again. Reporting the omission here is what turns a run-time
     * `AbstractMethodError` into a compile-time diagnostic that names the missing method and the
     * interface that asked for it.
     */
    private fun checkInterfacesImplemented(
        declaration: ClassDeclaration,
        classSymbol: ClassSymbol,
    ) {
        if (declaration.isInterface) return
        val provided = classSymbol.allFunctions()

        for (implemented in classSymbol.interfaceSymbols) {
            for ((name, overloads) in implemented.allFunctions()) {
                for (overload in overloads) {
                    if (!overload.isAbstract) continue
                    val implementedHere = provided[name].orEmpty().any {
                        it.parameterTypes == overload.parameterTypes && !it.isAbstract
                    }
                    if (implementedHere) continue
                    diagnostics.error(
                        "class '${declaration.name}' implements '${implemented.name}' but does not " +
                            "provide '${overload.name}(${overload.parameterTypes.joinToString(", ") {
                                it.displayName
                            }})'",
                        declaration.nameSpan,
                        notes = listOf(
                            diagnostics.note(
                                "'${overload.name}' is declared here",
                                overload.span,
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Analyses a constructor body, which is a body like any other except for what it may return.
     *
     * A constructor produces the object being built rather than a value, so a `return` with an
     * expression is rejected. The parameter scope, implicit `this` and field visibility all match an
     * ordinary instance method.
     */
    private fun checkConstructorBody(
        constructor: ConstructorDeclaration,
        declaration: ClassDeclaration,
        classSymbol: ClassSymbol,
    ) {
        val symbol = constructor.symbol ?: return

        pushScope(identifier = classSymbol)
        for (field in classSymbol.allFields().values) {
            scopes.last().symbols[field.name] = field
        }

        currentFunction = symbol
        implicitThisClass = classSymbol
        // A body starts from nothing known: facts about `val` fields do not carry in from whatever
        // was analysed before this constructor.
        resetNarrowings()
        heldPurrLocks.clear()
        pushScope(identifier = constructor)

        val parameterSymbols = mutableListOf<LocalSymbol>()
        for (parameter in constructor.parameters) {
            val parameterType = resolveType(parameter.declaredType)
            val local = LocalSymbol(
                name = parameter.name,
                type = parameterType,
                span = parameter.nameSpan,
                kind = VariableKind.VAL,
                isParameter = true,
            )
            parameterSymbols += local
            val existing = scopes.last().symbols[parameter.name]
            if (existing != null) {
                diagnostics.error(
                    "parameter '${parameter.name}' is already declared",
                    parameter.span,
                    notes = listOf(diagnostics.note("previous declaration is here", existing.span)),
                )
                continue
            }
            scopes.last().symbols[parameter.name] = local
        }
        constructor.parameterSymbols = parameterSymbols

        analyzeBlock(constructor.body)

        popScope()
        popScope()
        currentFunction = null
        implicitThisClass = null
        return
    }

    private fun checkFieldInitializer(field: FieldDeclaration) {
        val declaredType = field.resolvedType ?: return
        val initializer = field.initializer

        if (initializer == null) {
            if (!declaredType.hasImplicitDefault) {
                diagnostics.error(
                    "field '${field.name}' of type ${declaredType.displayName} must be initialized",
                    field.nameSpan,
                )
            }
            return
        }

        // Fields are deliberately out of scope here. Initializers all run in `<clinit>`, in
        // declaration order, so a *forward* reference would silently read the field's default value
        // rather than the value the author meant. Rejecting every reference is the conservative
        // choice, and it keeps the rule easy to state: a field initializer may only use parameters,
        // literals and calls.
        pushScope(identifier = null)
        // The declared type is passed as the context for the same reason a variable declaration
        // passes it: `val name: string? = null` is the whole point of the suffix, and a `null` with
        // no context to read it in would have no type to be.
        val valueType = analyzeExpression(initializer, declaredType)
        popScope()

        if (!isAssignable(valueType, declaredType)) {
            diagnostics.error(
                "field '${field.name}' is declared as ${declaredType.displayName} but its initializer " +
                    "has type ${valueType.displayName}",
                initializer.span,
            )
        }
    }

    private fun checkFunctionBody(function: FunctionDeclaration, classSymbol: ClassSymbol) {
        val symbol = function.symbol ?: return
        // An abstract function declares a signature and nothing else; there is no body to check.
        val body = function.body ?: return
        val declaredReturn = symbol.returnType

        // An inner scope holds the class' fields, so `println(field1)` resolves inside a method.
        pushScope(identifier = classSymbol)
        for (field in classSymbol.allFields().values) {
            scopes.last().symbols[field.name] = field
        }

        currentFunction = symbol
        // Only a non-static member has an instance to reach its instance members through.
        implicitThisClass = if (symbol.isStatic) null else classSymbol
        forwardsRaise = false
        // The body's own type parameters, so `value: T` inside `fun <T> f(value: T)` reads `Object`
        // rather than being looked up as a class and reported unresolved.
        val outerTypeParameters = currentTypeParameters
        currentTypeParameters = function.typeParameters
        // A body starts holding nothing: whatever `purr` blocks surrounded the *declaration* are not
        // around the code that runs when it is called.
        heldPurrLocks.clear()
        // A body starts from nothing known. Pending facts about `val` fields belong to whatever was
        // analysed before this method, and none of them was proved here.
        resetNarrowings()
        pushScope(identifier = function)

        for (parameter in parametersByFunction[function].orEmpty()) {
            val existing = scopes.last().symbols[parameter.name]
            if (existing != null) {
                diagnostics.error(
                    "parameter '${parameter.name}' is already declared",
                    parameter.span,
                    notes = listOf(diagnostics.note("previous declaration is here", existing.span)),
                )
                continue
            }
            scopes.last().symbols[parameter.name] = parameter
        }

        analyzeBlock(body)

        // A function's body is where groups are opened and installed, so this is the last moment one
        // that was never installed can be reported: past here the names are stale, and a group left
        // over from this body would be reported against the next one.
        reportUnusedGroups()
        groupsByName.clear()

        // A function with an `emit` clause is exempt from the "must return on every path" rule: a
        // `yield` is a legitimate way to leave it, and the value it carries — not a `return` — is what
        // the caller's `try` receives. Requiring a return as well would make every such function
        // awkward to write.
        if (!declaredReturn.isVoidOrError && !symbol.canRaise && !body.alwaysReturns()) {
            diagnostics.error(
                "function '${function.name}' declares that it returns ${declaredReturn.displayName} " +
                    "but its body can finish without executing a 'return' statement",
                body.span,
            )
        }

        // "Declares an `emit` clause but can never make use of it" is checked in
        // `linkScratchHandlers`, not here: whether the clause does any work depends on things only
        // known once every body has been walked — notably whether some `try` names this function as
        // its callee, which is the other way the clause earns its keep.

        popScope()
        popScope()
        currentFunction = null
        implicitThisClass = null
        currentTypeParameters = outerTypeParameters
    }

    // -------------------------------------------------------------- statements

    /** Analyses a block in a fresh scope. */
    private fun analyzeBlock(block: BlockStatement) {
        pushScope(identifier = block)
        val incoming = saveNarrowings()
        var terminated: Statement? = null
        for (statement in block.statements) {
            if (terminated != null) {
                // The sample marks the line after a departure as "Unreachable, notice as error level",
                // so this is an error rather than a warning. Still analysed, so that a second mistake
                // on the unreachable line is reported in the same run.
                diagnostics.error(
                    "unreachable statement: execution cannot continue past the preceding " +
                        "${terminated.describeTerminator()}",
                    statement.span,
                    notes = listOf(
                        diagnostics.note("the previous statement is here", terminated.span),
                    ),
                )
                terminated = null
            }
            analyzeStatement(statement)
            if (statement.terminatesFlow()) terminated = statement
            // A guard leaves a fact behind it: `if (x == null) { return }` is the ordinary way to
            // say "from here on, `x` is there", and every later statement in this block is on the
            // side of that test where it held.
            applyFacts(factsAfterExit(statement))
        }
        // A block is a scope for what is known as much as for what is named: a fact proved inside
        // one is about *this* run through it, so it does not outlive the block.
        restoreNarrowings(incoming)
        popScope()
    }

    /**
     * The facts that hold for the code after [statement], on the readings where it did not leave.
     *
     * Only a guard says anything. An `if` with one arm that always returns proves the condition's
     * opposite for whatever follows — which is the whole of what `if (x == null) { return }` is
     * for — and an `if` whose two arms both leave is followed by nothing at all, so it says
     * nothing. Anything else is not a guard and leaves the flow as it found it.
     */
    private fun factsAfterExit(statement: Statement): Map<Any, NekoType> {
        if (statement !is IfStatement) return emptyMap()
        val thenExits = statement.thenBranch.alwaysReturns()
        val elseExits = statement.elseBranch?.alwaysReturns() == true
        return when {
            thenExits && elseExits -> emptyMap()
            thenExits -> factsFalse(statement.condition)
            elseExits -> factsTrue(statement.condition)
            else -> emptyMap()
        }
    }

    private fun analyzeStatement(statement: Statement) {
        when (statement) {
            is BlockStatement -> analyzeBlock(statement)
            is VariableDeclarationStatement -> analyzeVariableDeclaration(statement)
            is AssignmentStatement -> analyzeAssignment(statement)
            is ExpressionStatement -> analyzeExpression(statement.expression)
            is ReturnStatement -> analyzeReturn(statement)
            is YieldStatement -> analyzeYield(statement)
            is IfStatement -> analyzeIf(statement)
            is WhileStatement -> analyzeWhile(statement)
            is ForStatement -> analyzeFor(statement)
            is ForEachStatement -> analyzeForEach(statement)
            is IncrementStatement -> analyzeIncrement(statement)
            is WhenStatement -> analyzeWhen(statement)
            is NyanStatement -> analyzeNyan(statement)
            is TryStatement -> analyzeTry(statement)
            is TryScratchStatement -> analyzeTryScratch(statement, wantedType = null)
            is PurrStatement -> analyzePurr(statement)
            is PreGroupDeclaration -> analyzePreGroup(statement)
            is BlepDeclaration -> analyzeBlep(statement)
            is IndexAssignmentStatement -> analyzeIndexAssignment(statement)
        }
    }

    /**
     * A locking block — `purr`, `purred`, `purrcls` or `purredcls` — checked for the two mistakes that
     * would otherwise show up as a hang rather than an error.
     *
     *  * An **instance** lock is a lock on `this`, so it only exists where `this` does. A `static`
     *    function has no instance, which is why `purr`/`purred` there are refused and the reader is
     *    told to write the `cls` spelling — the class lock needs no instance and is the one they
     *    meant.
     *  * `StampedLock` is **not reentrant** — in either mode. Taking a lock this thread already holds
     *    blocks forever with no error, no timeout and no stack to look at, so it is worth catching at
     *    compile time. The *mode* is deliberately not part of the identity: `purred` inside `purr` on
     *    one lock, `purr` inside `purred`, and `purred` inside `purred` are all deadlocks, since the
     *    JVM has no owner to notice the same thread asking twice. Only a *different* lock nests
     *    safely, which is what makes `purrcls { purr { } }` the way to wrap a per-object guard in a
     *    class-wide one. Nesting is tracked through [heldPurrLocks], which has to be per-method
     *    because a lambda's body is a different method that inherits nothing.
     */
    private fun analyzePurr(statement: PurrStatement) {
        if (statement.lock == PurrLock.INSTANCE && implicitThisClass == null) {
            diagnostics.error(
                "this '${statement.keyword}' holds the lock of 'this', but this code has no instance: " +
                    "a 'static' function belongs to the class rather than to an object, so write " +
                    "'${classLockKeyword(statement.mode)} { ... }' to hold the class lock instead",
                statement.keywordSpan,
            )
        }

        if (heldPurrLocks.contains(statement.lock)) {
            diagnostics.error(
                "this '${statement.keyword}' takes the ${describePurrLock(statement.lock)} lock that " +
                    "is already held around it, and a StampedLock cannot be taken twice by one " +
                    "thread — the program would block forever",
                statement.keywordSpan,
            )
        }

        heldPurrLocks.addLast(statement.lock)
        analyzeBlock(statement.body)
        heldPurrLocks.removeLast()
    }

    /** How a [PurrStatement]'s lock is named in a diagnostic. */
    private fun describePurrLock(lock: PurrLock): String =
        if (lock == PurrLock.CLASS) "class" else "instance"

    /**
     * The keyword that holds the class lock in [mode], for a diagnostic suggesting the fix.
     *
     * The suggestion has to keep the mode: a reader who wrote `purred` in a `static` function wants
     * the class lock *read*, which is `purredcls`, not `purrcls`.
     */
    private fun classLockKeyword(mode: PurrMode): String =
        if (mode == PurrMode.READ) "purredcls" else "purrcls"

    /** `array[index] = value` */
    private fun analyzeIndexAssignment(statement: IndexAssignmentStatement) {
        val arrayType = requireArray(analyzeExpression(statement.array), statement.array.span)
        val elementType = arrayElementType(arrayType, statement.array.span)
        analyzeIndex(statement.index, statement.indexSpan)

        val valueType = analyzeExpression(statement.value, elementType)
        if (elementType != null && !isAssignable(valueType, elementType)) {
            diagnostics.error(
                "cannot assign a value of type ${valueType.displayName} to an element of type " +
                    elementType.displayName,
                statement.value.span,
            )
        }
    }

    /**
     * The element type of [arrayType], reporting when it is not an array at all.
     *
     * Returns `null` after reporting, so a caller can skip the follow-on errors that would
     * otherwise pile up from the same mistake.
     */
    private fun arrayElementType(arrayType: NekoType, span: Span): NekoType? {
        if (arrayType.isError) return null
        if (arrayType is ArrayType) return arrayType.element
        diagnostics.error(
            "only an array can be indexed, but this expression has type ${arrayType.displayName}",
            span,
        )
        return null
    }

    /** Checks that an index expression is an `int`, reporting at the index itself. */
    private fun analyzeIndex(index: Expression, span: Span) {
        val indexType = analyzeExpression(index)
        if (!indexType.isError && indexType != IntType) {
            diagnostics.error(
                "an array index must be int but has type ${indexType.displayName}",
                span,
            )
        }
    }

    /**
     * The array an index reads, refusing an array that may be absent.
     *
     * Indexing is the third way to dereference a value, and a nullable array has to be refused exactly
     * as a nullable object is. There is no safe spelling to suggest — Neko has no `?[` form — so the
     * reader is told what is possible instead: a nullable array has to be compared against `null`
     * before it is indexed, or the value has to be given a non-nullable type.
     */
    private fun requireArray(arrayType: NekoType, span: Span): NekoType {
        if (arrayType is NullableType) {
            diagnostics.error(
                "cannot index a value of type ${arrayType.displayName}, which may be null; test it " +
                    "with '== null' before indexing it, or give the value a non-nullable type",
                span,
            )
            return ErrorType
        }
        return arrayType
    }

    /**
     * Reports a use of `scratch` or `nyan` in a program that does not declare `EmitYieldException`.
     *
     * The class is no longer shipped as a Java class with the compiler: it is the one file in `stdlib/`
     * that the language genuinely needs, and the compiler compiles it ahead of every program. So the
     * check is now an ordinary one — is the declaration in the program? — asked of the program's own
     * symbols rather than of the running JVM's classpath.
     *
     * The consequence is the useful one: a program that loses the library (a build pointed at sources
     * it cannot see, an installation whose resources are missing) is told which class is missing and
     * by name, at the first `scratch` or `nyan` — rather than a `ClassNotFoundException` at run time,
     * from a class file that named something nothing would ever emit. `yield` needs no such check:
     * the class is also what `yield` *throws*, so a file that reaches it has already had to name it.
     */
    private fun checkStandardLibrary(span: Span) {
        if (standardLibraryChecked) return
        standardLibraryChecked = true

        if (classesBySimpleName.containsKey(EMIT_YIELD_EXCEPTION_SIMPLE_NAME)) return

        diagnostics.error(
            "'scratch' and 'nyan' need ${EMIT_YIELD_EXCEPTION_SIMPLE_NAME}, which the compiler " +
                "compiles from its own standard library ahead of every program — this installation's " +
                "copy is missing or declares it under another name",
            span,
        )
    }

    /**
     * `nya condition`, `nya condition { ... }`, `nya condition { ... } else { ... }` — checks the
     * condition, and says what happens when it does not hold.
     *
     * The condition is an ordinary expression, and the three writings differ in what they do when it
     * goes wrong:
     *
     *  * **Bare.** `nya a == b` is a `bool` test and nothing else: a false condition throws an
     *    `AssertionError`, so the only way past the statement is the reading where the condition held.
     *    The fact is therefore applied to what follows, and the check narrows exactly as a guard does
     *    — `nya h != null` makes the rest of the block a place where `h` is there. It is also why the
     *    condition has to *be* a `bool`: a check with no other outcome to describe could never fail,
     *    so a reader who wrote one meant something else.
     *  * **With a failure block.** The block runs when the condition does not hold and control carries
     *    on after it. "Does not hold" is deliberately wider than "is false": the block also catches an
     *    `AssertionError` the condition *threw* — its own bare `nya`, or one inside a function it
     *    called — which is what makes `nya connect() { ... }` a call whose failure is handled, and is
     *    why the condition may be of any type at all, `void` included. There is no `bool` to demand of
     *    an expression whose failure is already described.
     *  * **With both blocks.** The construct is worth a value. Each block `yield`s it and the two must
     *    agree: they are two answers to one question, so a disagreement means the expression means
     *    nothing.
     *
     * Each block is analysed under what its own reading proves. The `else` block is reached only when
     * the condition ran to a value, so the true reading's facts hold there. The failure block is
     * reached either by a false condition or by a throw, so it gets only the facts that survive both
     * — see [failureFacts].
     */
    private fun analyzeNyaExpression(expression: NyaExpression, expectedType: NekoType?): NekoType {
        val conditionType = analyzeExpression(expression.condition)

        val failureBlock = expression.failureBlock
        val elseBlock = expression.elseBlock

        // Only the bare form has to answer a `bool`. With a failure block the condition is watched for
        // a throw as well as for a false answer, and an expression that throws has no type it had to
        // be — so `nya close() { ... }` is a call whose failure is handled, whatever it answers.
        if (failureBlock == null) {
            requireBool(conditionType, expression.condition.span, "nya")
        }

        if (elseBlock == null) {
            if (failureBlock == null) {
                applyFacts(factsTrue(expression.condition))
                return VoidType
            }
            analyzeNyaBlock(expression, failureBlock, holds = false, facts = failureFacts(expression))
            return VoidType
        }

        // From here the construct is worth a value, so both blocks are analysed under one shared
        // state: the first `yield` in either of them fixes the type the other has to match.
        //
        // The `else` block is the easy one: a throw never reaches it, so it is entered only on the
        // reading where the condition ran to a value, and the true facts hold there. The failure
        // block is the one a throw shares with a false answer, so it gets [failureFacts] instead.
        val saved = currentNya
        val state = NyaValue(expression, expectedType)
        currentNya = state
        try {
            failureBlock?.let {
                analyzeNyaBlock(expression, it, holds = false, facts = failureFacts(expression), state = state)
            }
            analyzeNyaBlock(
                expression,
                elseBlock,
                holds = true,
                facts = factsTrue(expression.condition),
                state = state,
            )
        } finally {
            currentNya = saved
        }

        if (failureBlock == null) {
            // The parser already reported this, but the node still exists and generation would read a
            // result type off it, so the answer stops here rather than guessing at what is missing.
            return ErrorType
        }

        if (expression.yieldedType == null) {
            // Neither block produced a value, so the expression would answer nothing.
            diagnostics.error(
                "this 'nya' is used as a value, so both of its blocks must 'yield' the value it " +
                    "is worth",
                expression.span,
            )
            return ErrorType
        }

        if (!state.failureYielded) {
            diagnostics.error(
                "the failure block of this 'nya' never 'yield's, so there is no value for the " +
                    "case where the condition is false",
                failureBlock.span,
            )
        }
        if (!state.elseYielded) {
            diagnostics.error(
                "the 'else' block of this 'nya' never 'yield's, so there is no value for the " +
                    "case where the condition holds",
                elseBlock.span,
            )
        }

        return expression.yieldedType!!
    }

    /**
     * Analyzes one of a `nya`'s blocks under what its own reading of the condition proves.
     *
     * The facts are saved and restored around the block for the same reason an `if` arm's are: what a
     * test proves is true *inside* the code it guards, never after it. [facts] is what this block gets
     * to assume — see [analyzeNyaExpression] for why the `else` block can take the true reading's
     * facts and the failure block often cannot.
     */
    private fun analyzeNyaBlock(
        expression: NyaExpression,
        block: BlockStatement,
        holds: Boolean,
        facts: Map<Any, NekoType>,
        state: NyaValue? = null,
    ) {
        val incoming = saveNarrowings()
        applyFacts(facts)
        if (state != null) state.inFailureBlock = !holds
        analyzeBlock(block)
        restoreNarrowings(incoming)
    }

    /**
     * What a `nya`'s failure block may assume, given that it is entered two different ways.
     *
     * The block runs when the condition answers `false` *and* when the condition throws an
     * `AssertionError`, so a fact may only be applied if it holds on **both** of those runs. What a
     * false condition proves is a fact about the second run only: in
     * `nya h == null || load() { ... }` the block is a place where `h` is known to be there *if* the
     * check ran to an answer, but a throw out of `load()` gets there with `h` still null — so
     * believing the fact would let the block call `h.value` and crash, which is exactly the failure
     * the analyzer exists to catch.
     *
     * So the question is only whether the throw path is reachable at all: a condition built from
     * literals, names and comparisons cannot throw an `AssertionError` itself, so the false reading
     * is the only way in and its facts are sound. One that calls anything, or contains a `nya` of its
     * own, can — and then the block is analysed with no new facts, so a nullable `h` stays nullable
     * there and must still be tested before use rather than trusted below a `!= null` that may never
     * have been reached. Facts from *around* the `nya` are untouched either way: those were proved
     * before the condition ran, so both entry paths share them.
     */
    private fun failureFacts(expression: NyaExpression): Map<Any, NekoType> =
        if (canThrowAssertion(expression.condition)) emptyMap() else factsFalse(expression.condition)

    /**
     * `scratch e1, e2` — enters the `try` handler whose `emit [...]` signature these values match.
     *
     * Only meaningful inside a function that declared an `emit` clause, and the values must match that
     * clause one for one: the clause is what says which handler out there is the right one, so a
     * mismatch means no handler could ever be reached. What the site itself is worth is left to
     * [linkScratchHandlers], which needs every site before it can answer.
     *
     * A `scratch` is *not* a departure — it is an ordinary call that returns whatever the handler
     * answers — so unlike the `raise` it replaced, the statement after it is reachable.
     *
     * [expectedType] is what the position asks for — the declared type of the variable being
     * initialised — and is `null` when the value is discarded. That is the whole of what decides the
     * handler's return type.
     */
    private fun analyzeScratch(expression: ScratchExpression, expectedType: NekoType?): NekoType {
        val function = currentFunction
        checkStandardLibrary(expression.keywordSpan)

        if (function == null || !function.canRaise) {
            for (value in expression.values) analyzeExpression(value)
            diagnostics.error(
                "'scratch' is only allowed inside a function that declares an 'emit' clause",
                expression.keywordSpan,
            )
            return ErrorType
        }

        val raised = function.emitTypes
        if (expression.values.size != raised.size) {
            for (value in expression.values) analyzeExpression(value)
            diagnostics.error(
                "'scratch' must provide ${raised.size} value(s) to match the 'emit' clause of " +
                    "'${function.name}' but ${expression.values.size} " +
                    "${if (expression.values.size == 1) "was" else "were"} provided",
                expression.keywordSpan,
                notes = listOf(
                    diagnostics.note("'${function.name}' declares its 'emit' clause here", function.span),
                ),
            )
            return ErrorType
        }

        for ((index, value) in expression.values.withIndex()) {
            val actual = analyzeExpression(value, raised[index])
            if (!isAssignable(actual, raised[index])) {
                diagnostics.error(
                    "value ${index + 1} of 'scratch' is declared as ${raised[index].displayName} " +
                        "by the 'emit' clause but has type ${actual.displayName}",
                    value.span,
                )
            }
        }

        // The signal is an ordinary expression to type, but it has a rule of its own: only an enum
        // constant can be one, because the branch it selects is an identity comparison against that
        // constant. Naming nothing is fine and passes `null`, which matches no branch.
        expression.signal?.let { signal ->
            analyzeExpression(signal)
            if (!signal.type.isError && !isEnumConstantAccess(signal)) {
                diagnostics.error(
                    "the signal of a 'scratch' must name an enum constant such as " +
                        "'NekoSignal.ADD', because the handler's branches compare against it",
                    expression.signalSpan ?: signal.span,
                )
            }
        }

        // A clause that named a result fixes what the site is worth, so the handler's promise is what
        // the reader sees here — `emit [int, int] -> int` makes `v: int = scratch a, b` an ordinary
        // `int` initialisation without the site having to repeat the type. A clause that named none
        // leaves the site worth nothing, and a position that asks for a value is then the mistake.
        function.emitResultType?.let { declared ->
            if (expectedType != null && !isAssignable(declared, expectedType)) {
                diagnostics.error(
                    if (declared === VoidType) {
                        "this 'scratch' answers nothing, because '${function.name}' names no result " +
                            "in its 'emit' clause; drop the assignment to keep the value it hands " +
                            "to the handler on its own"
                    } else {
                        "this 'scratch' answers ${declared.displayName}, as its 'emit' clause " +
                            "declares, but this position wants ${expectedType.displayName}"
                    },
                    expression.keywordSpan,
                    notes = listOf(
                        diagnostics.note(
                            "'${function.name}' declares its 'emit' clause here",
                            function.span,
                        ),
                    ),
                )
                // One mistake, one diagnostic: answering `ErrorType` stops the position's own check
                // from reporting the same disagreement a second time.
                return ErrorType
            }
            return declared
        }

        // Unreachable in a legal program: every clause answers a result, `void` included. Kept as the
        // recovery shape while the program is already known to be broken.
        return expectedType ?: ObjectType
    }

    /**
     * `try test(10) { emit1, emit2 -> ... }`
     *
     * The callee must be a function that declares an `emit` clause — otherwise there is nothing to
     * catch, and the extractor names values that could never arrive. The extractor parameters take
     * their types from that clause, so they need no annotations, and naming fewer than the clause
     * declares is allowed (the rest are simply not bound).
     *
     * The same checks serve [TryExpression]; only the value the construct is worth differs, which
     * [analyzeTryExpression] adds on top.
     */
    private fun analyzeTry(statement: TryStatement) {
        analyzeExtractorTry(statement)
    }

    /**
     * `try pre: label` —opens a handler group.
     *
     * Opening a group installs nothing, so there is nothing to type-check here; the name is recorded
     * for the `blep` branches that follow and for the `try ... : label` that closes it. A duplicate
     * name is refused because the two groups' branches would otherwise merge into one `dispatch`
     * with duplicate signals.
     */
    private fun analyzePreGroup(statement: PreGroupDeclaration) {
        val existing = groupsByName[statement.name]
        if (existing != null) {
            diagnostics.error(
                "a handler group named '${statement.name}' is already open",
                statement.nameSpan,
                notes = listOf(diagnostics.note("the previous group is here", existing.nameSpan)),
            )
            return
        }
        groupsByName[statement.name] = HandlerGroup(statement.name, statement.nameSpan)
    }

    /**
     * The handler groups opened in this function, by name.
     *
     * Cleared when a function's body starts, because a group is a construct of one body: its branches
     * and the `try` that installs it have to be written side by side, and letting a name leak into
     * the next function would make a typo there silently extend a group elsewhere.
     */
    private val groupsByName = LinkedHashMap<String, HandlerGroup>()

    /**
     * `blep: label NekoSignal.ADD { a, b -> ... }` —one branch of a handler group.
     *
     * The signal must name an enum constant, because the generated `dispatch` compares the incoming
     * signal against it with `==`. Anything else would compare against a value no site could pass,
     * which is worth saying now rather than leaving as a branch that never runs.
     *
     * The branch's *body* is analysed later, by [analyzeBlepBody], once the `try` that closes the
     * group has been read: a branch is usually written above that `try`, and it is the `try`'s callee
     * that supplies the `emit` clause the parameters and `nyan` are checked against. Analysing the
     * body here would check it against nothing.
     */
    private fun analyzeBlep(statement: BlepDeclaration) {
        val signalType = analyzeExpression(statement.signal)
        if (!signalType.isError && !isEnumConstantAccess(statement.signal)) {
            diagnostics.error(
                "the signal of a 'blep' branch must name an enum constant such as 'NekoSignal.ADD', " +
                    "because the generated branch compares against it",
                statement.signal.span,
            )
        }
        statement.signalType = signalType

        val group = groupsByName[statement.groupName]
        if (group == null) {
            diagnostics.error(
                "there is no handler group named '${statement.groupName}'; write " +
                    "'try pre: ${statement.groupName}' above its branches to open one",
                statement.groupNameSpan,
            )
            return
        }
        group.branches += statement
        statement.group = group
    }

    /**
     * Whether [expression] is a dotted access naming an entry of an enum.
     *
     * The shape is what distinguishes a signal from any other value: `NekoSignal.ADD` is a constant
     * the compiler emits as a static field, so only a dotted access can name one. The receiver's
     * resolved type is asked whether it is a `miao class` —which is what a signal has to be, because
     * the generated branch compares against the constant's identity.
     */
    private fun isEnumConstantAccess(expression: Expression): Boolean {
        if (expression !is MemberAccessExpression) return false
        val receiver = expression.receiver.type as? ClassType ?: return false
        return classesBySimpleName[receiver.simpleName]?.firstOrNull()?.isMiao == true
    }

    /**
     * Checks one `blep` body against the clause of the `try` that installs its group.
     *
     * All the branches of a group answer the same `emit` clause, so their parameters are bound to the
     * same value types and their `nyan`s to the same result. That is what makes them
     * interchangeable: the generated `dispatch` picks one by signal, and every one of them must be a
     * legal answer to the call it is serving.
     */
    private fun analyzeBlepBody(
        branch: BlepDeclaration,
        signature: List<NekoType>,
        resultType: NekoType?,
    ) {
        if (branch.parameters.size > signature.size) {
            val extra = branch.parameters[signature.size]
            diagnostics.error(
                "this 'blep' names ${branch.parameters.size} value(s) but the group's 'emit' clause " +
                    "declares ${signature.size}",
                extra.span,
            )
        }

        val savedFunction = currentFunction
        val savedExtractor = currentExtractor
        val savedBoundary = currentBoundaryIdentity
        val savedNya = currentNya
        pushScope(identifier = branch)
        // The body is a handler like any other: `nyan` leaves it, and it is its own method boundary.
        currentExtractor = branch
        currentBoundaryIdentity = branch
        currentNya = null
        branch.handler.signature = signature
        branch.handler.emitResultType = resultType

        for ((index, parameter) in branch.parameters.withIndex()) {
            val type = signature.getOrNull(index) ?: ErrorType
            parameter.resolvedType = type
            val symbol = LocalSymbol(
                name = parameter.name,
                type = type,
                span = parameter.nameSpan,
                kind = VariableKind.VAL,
                isParameter = true,
                enclosingLambda = currentBoundaryIdentity,
            )
            val existing = scopes.last().symbols[parameter.name]
            if (existing != null) {
                diagnostics.error(
                    "parameter '${parameter.name}' is already declared",
                    parameter.nameSpan,
                    notes = listOf(diagnostics.note("previous declaration is here", existing.span)),
                )
                continue
            }
            scopes.last().symbols[parameter.name] = symbol
            parameter.symbol = symbol
        }

        analyzeBlock(branch.body)

        currentExtractor = savedExtractor
        currentBoundaryIdentity = savedBoundary
        popScope()
        currentFunction = savedFunction
        currentNya = savedNya
    }

    /**
     * `try call() scratch v1, v2` — catch a Java exception out of [TryScratch.call] and answer it by
     * entering the handler the `scratch` names.
     *
     * Three things have to hold, and each one is a real constraint rather than a formality:
     *
     *  * **The `scratch` must be legal where it is written.** It is analysed as an ordinary
     *    `scratch`, so the enclosing function has to declare an `emit` clause and the values must
     *    match it — that clause is what says which handler out there receives this failure. Without
     *    it the construct would have no way to name a destination.
     *  * **The call has to be able to return a value** for the expression form, since the value of the
     *    construct is that return value on the path where nothing was thrown. `t()` returning `void`
     *    is fine for the statement form, which discards everything.
     *  * **The handler's answer and the call's return value must agree**, because they are two
     *    answers to the same question — exactly the rule a `yield`-carrying `try` already follows.
     *
     * The `scratch` is analysed with [wantedType] as the expected type, so the handler's return type
     * is fixed by *this* construct in the same way a written-out `scratch` site fixes it.
     */
    private fun analyzeTryScratch(construct: TryScratch, wantedType: NekoType?): NekoType {
        checkStandardLibrary(construct.keywordSpan)

        // The call is resolved through the ordinary path: unlike the handler-declaring form, this
        // construct makes no demand that the callee declare an `emit` clause. It catches a Java
        // exception, which any call can throw.
        val callType = analyzeExpression(construct.call)

        // The `scratch` is analysed where it stands, which is what enforces the `emit` requirement.
        // It goes through `analyzeExpression` rather than calling `analyzeScratch` directly, because
        // only that path records the site's own type on the node — and the code generator needs it
        // to know whether calling the handler leaves a value on the stack to pop.
        val answered = analyzeExpression(construct.scratch, wantedType)

        val wanted = wantedType?.takeIf { it !== ErrorType }
        if (wanted == null) {
            // A statement, or a value nobody asked for: the handler's answer is simply dropped.
            return callType
        }

        if (callType === ErrorType) return wanted

        if (callType === VoidType) {
            diagnostics.error(
                "this 'try' is used as a value, but '${construct.call.callee}' returns " +
                    "${callType.displayName}, so the path where nothing is thrown produces nothing; " +
                    "drop the assignment to keep the failure handling on its own",
                construct.call.span,
            )
            return wanted
        }

        // The handler answers `answered`, which the scratch site asked to be `wanted`. Both paths of
        // this construct have to be the same type, for the same reason a `yield` must agree with the
        // call's return type in the other form.
        if (!isAssignable(callType, wanted)) {
            diagnostics.error(
                "this 'try' is worth ${wanted.displayName} when the handler answers, but " +
                    "'${construct.call.callee}' returns ${callType.displayName} when nothing is thrown",
                construct.scratch.keywordSpan,
            )
        }
        return wanted
    }

    /**
     * `r: int = try test(10) { a, b -> yield 1 }`
     *
     * The expression form differs from the statement form in exactly one respect: it is worth a
     * value. Which value depends on the path taken, and the two are not the same thing:
     *
     *  * **The call returned normally.** The `try` is worth the call's own return value. No handler
     *    ran, so neither a `nyan` nor a `yield` had anything to say.
     *  * **A `yield` ran.** The exception it threw carried the value, so that is what the `try` is
     *    worth — which is why the statement form, worth nothing, refuses a `yield` outright.
     *
     * A `nyan` is deliberately *not* part of this: it answers the `scratch` site that entered the
     * handler, and that site is somewhere else entirely — usually a different function. So a `try`
     * expression whose handler only ever `nyan`s still needs the normal path to produce a value, and
     * that is what the call's return type already is.
     */
    private fun analyzeTryExpression(expression: TryExpression): NekoType {
        val callee = analyzeExtractorTry(expression)
        val normalResult = callee?.returnType ?: ErrorType

        val yielded = expression.yieldedType
        if (yielded != null) {
            // The `yield` path has to agree with the normal one: they are two answers to the same
            // question, so a disagreement means the expression means nothing.
            if (!isAssignable(yielded, normalResult)) {
                diagnostics.error(
                    "this 'try' is worth ${normalResult.displayName} when the call returns, but its " +
                        "handler 'yield's ${yielded.displayName} when it does not",
                    expression.extractorBody.span,
                )
            }
            return normalResult
        }

        // Nothing `yield`s. That is fine as long as the call itself answers something, because then
        // the normal path supplies the value — a handler that only `nyan`s is exactly this case.
        if (normalResult !== VoidType && normalResult !== ErrorType) return normalResult

        // A `void` call leaves no value on the normal path, and nothing `yield`s on the other, so the
        // expression could never be worth anything whatever happened.
        diagnostics.error(
            "this 'try' is used as a value, but '${expression.call.callee}' returns " +
                "${normalResult.displayName} and its handler never 'yield's one, so neither path " +
                "produces a value",
            expression.extractorBody.span,
        )
        return ErrorType
    }

    /**
     * Records [statement] as a `try` handler and gives it the facts code generation needs.
     *
     * No two handlers can clash any more, and that is the point of the change this belongs to: a
     * handler is no longer found by matching an `emit` signature across the whole program. Each
     * `try` builds a handler object and hands it to the one call it wraps, so ten `try` blocks may
     * answer the same clause and each satisfies its own call.
     *
     * What is recorded is the clause's value types — what the body's parameters are declared as, and
     * therefore what the interface's `Object`s are converted to on entry. The class name is chosen by
     * the code generator when it emits, because it numbers classes per emitted method rather than per
     * source function.
     */
    private fun registerHandler(
        statement: ExtractorTry,
        signature: List<NekoType>,
        resultType: NekoType? = null,
    ) {
        statement.handler.signature = signature
        statement.handler.emitResultType = resultType
        handlers += statement
    }

    /**
     * `nyan` or `nyan value` — leaves the handler, optionally answering a value.
     *
     * A `nyan` is the handler's ordinary `return`: the generated `dispatch` method answers an
     * `Object`, so a valued `nyan` boxes its value on the way out and a bare `nyan` answers `null`.
     *
     * The type is constrained by the clause the callee wrote down: an `emit [...] -> R` clause
     * promises its handler answers an `R`, so a valued `nyan` here must produce one. A bare `nyan`
     * stays legal in that case too — it answers `null`, which the site reads as `R`'s default —
     * because "I have nothing to say" is a real answer, not a hole.
     *
     * A clause that named no result promises nothing, so the only `nyan` it admits is the bare one.
     * That is the whole point of writing `emit [int]` rather than `emit [int] -> int`: the handler
     * says it is finished and the `scratch` site is a statement rather than a value.
     */
    private fun analyzeNyan(statement: NyanStatement) {
        val value = statement.value
        val handler = currentExtractor
        if (handler == null) {
            value?.let { analyzeExpression(it) }
            diagnostics.error(
                "'nyan' can only be used directly inside the 'try' handler it leaves; write " +
                    "'return' to leave the enclosing function, or 'yield' to answer the 'try' itself",
                statement.keywordSpan,
            )
            return
        }

        val promised = handler.handler.emitResultType
        if (value == null) {
            // A bare `nyan` answers the type's default; nothing to check against the promise.
            return
        }
        if (promised == null) {
            // No clause was registered at all, which only happens after a `try` whose callee was
            // already reported: the value still needs analysing, but there is nothing to promise
            // against.
            analyzeExpression(value)
            return
        }

        if (promised === VoidType) {
            // The clause promises nothing, so a valued `nyan` has nothing to answer with. Said in
            // those words rather than as a type mismatch: `void` is not a type the reader can write a
            // value of, so "write void" would be advice they cannot take.
            val answered = analyzeExpression(value)
            diagnostics.error(
                "this 'nyan' answers ${answered.displayName}, but the 'emit' clause promises no " +
                    "value; write a bare 'nyan' to leave the handler",
                value.span,
            )
            return
        }

        val answered = analyzeExpression(value, promised)
        if (!isAssignable(answered, promised)) {
            diagnostics.error(
                "this 'nyan' answers ${answered.displayName}, but the 'emit' clause promises " +
                    "${promised.displayName}; write ${promised.displayName}, or a bare 'nyan' to " +
                    "let each 'scratch' site take its default",
                value.span,
            )
        }
    }

    /**
     * The part both `try` forms share: check the callee may reach a handler, then analyze the
     * extractor body in a scope binding the values it named.
     *
     * Returns the resolved callee, or `null` after reporting why the call cannot be caught.
     */
    private fun analyzeExtractorTry(statement: ExtractorTry): FunctionSymbol? {
        val call = statement.call
        checkStandardLibrary(statement.keywordSpan)
        val symbol = try {
            resolvingTryCallee = true
            resolveRaiseableCallee(call)
        } finally {
            resolvingTryCallee = false
        }

        val expected = symbol?.emitTypes.orEmpty()

        // Every handler is recorded whether or not its callee resolved, because a `scratch` site
        // matching its signature still needs somewhere to point. A handler whose own `try` is already
        // reported as broken then produces one error rather than a second, unrelated one.
        if (symbol != null) {
            registerHandler(statement, expected, symbol.emitResultType)
        }
        // The call is resolved here rather than through `analyzeExpression`, so its type has to be
        // recorded by hand: code generation reads it to know what the call returns on the path where
        // it returns normally, which is where a `try` expression's value comes from.
        call.resolvedType = symbol?.returnType ?: ErrorType
        for (argument in call.arguments) analyzeExpression(argument)

        if (symbol == null) {
            // Report the callee first — "not a function with an 'emit' clause" is only actionable once
            // the reader knows the call itself is fine. Resolve the receiver too, for `obj.f(...)`.
            analyzeTryExtractor(statement, emptyList())
            return null
        }

        if (statement.extractorParameters.size > expected.size) {
            val extra = statement.extractorParameters[expected.size]
            diagnostics.error(
                "this 'try' names ${statement.extractorParameters.size} value(s) but " +
                    "'${symbol.name}' declares ${expected.size} in its 'emit' clause",
                extra.span,
            )
        }

        analyzeTryExtractor(statement, expected)

        if (statement is TryStatement) installGroup(statement, expected)

        return symbol
    }

    /**
     * Handles the trailing `: label` of a `try`, which makes it the `try` that installs a group.
     *
     * The branches declared under `try pre: label` become this handler's `if`s and this body becomes
     * the `else`, so it is only now —once the callee's `emit` clause is known —that a branch's
     * parameters and `nyan` can be checked against anything.
     *
     * Only the statement form may install a group. The expression form is worth a `yield`, which a
     * branch cannot produce, and requiring that would make the two spellings of one group answer
     * differently.
     */
    private fun installGroup(statement: TryStatement, expected: List<NekoType>) {
        val name = statement.groupName ?: return
        val span = statement.groupNameSpan ?: statement.keywordSpan
        val group = groupsByName[name]
        if (group == null) {
            diagnostics.error(
                "there is no handler group named '$name'; write 'try pre: $name' above this 'try' to " +
                    "open one",
                span,
            )
            return
        }
        if (group.installer != null) {
            diagnostics.error(
                "the handler group '$name' is already installed",
                span,
                notes = listOf(diagnostics.note("the previous installation is here", group.installer!!.span)),
            )
            return
        }
        group.installer = statement
        statement.handler.branches = group.branches
        for (branch in group.branches) {
            branch.group = group
            analyzeBlepBody(branch, expected, statement.handler.emitResultType)
        }
    }

    /**
     * Reports a group that was opened but never installed.
     *
     * Its branches would never run, and —worse than a branch that never matches —the `try` they were
     * meant to extend is a plain handler, so a `scratch` naming a signal would silently fall through
     * to whatever that handler answers. Reported once per function, at the last chance to fix it.
     */
    private fun reportUnusedGroups() {
        for (group in groupsByName.values) {
            if (group.installer != null) continue
            diagnostics.error(
                "the handler group '${group.name}' is opened but never installed: no " +
                    "'try ... : ${group.name}' follows its branches, so they would never run",
                group.nameSpan,
            )
        }
    }

    /**
     * Analyzes the extractor body in its own scope, binding the parameters it named to the types the
     * callee's `emit` clause declares.
     *
     * The block becomes a method of a generated class, so it is a *method boundary*: its own
     * parameters and locals live in that method, and anything else it names is captured into a field
     * of the class and passed in through the constructor. That is what lets a handler read an
     * enclosing local, which it could not do when the block was a `static` method.
     */
    private fun analyzeTryExtractor(statement: ExtractorTry, expected: List<NekoType>) {
        val savedFunction = currentFunction
        val savedExtractor = currentExtractor
        // The identity the body's own declarations are stamped with, so the code generator can tell
        // them apart from the enclosing locals it has to capture. The `try` node is the identity: it
        // is stable, unique, and already carried by the construct down to code generation.
        val savedBoundary = currentBoundaryIdentity
        // It is its own home for a `yield`: a `yield` written here produces the extractor's value,
        // not that of a `nya` value form the `try` happens to be written inside.
        val savedNya = currentNya
        pushScope(identifier = statement)
        currentExtractor = statement
        currentBoundaryIdentity = statement
        currentNya = null

        for ((index, parameter) in statement.extractorParameters.withIndex()) {
            val type = expected.getOrNull(index) ?: ErrorType
            parameter.resolvedType = type
            val symbol = LocalSymbol(
                name = parameter.name,
                type = type,
                span = parameter.nameSpan,
                kind = VariableKind.VAL,
                isParameter = true,
                // The body is a method of its own, so these are *its* locals rather than captures.
                enclosingLambda = currentBoundaryIdentity,
            )
            val existing = scopes.last().symbols[parameter.name]
            if (existing != null) {
                diagnostics.error(
                    "extractor parameter '${parameter.name}' is already declared",
                    parameter.nameSpan,
                    notes = listOf(diagnostics.note("previous declaration is here", existing.span)),
                )
                continue
            }
            scopes.last().symbols[parameter.name] = symbol
            parameter.symbol = symbol
        }

        analyzeBlock(statement.extractorBody)

        currentExtractor = savedExtractor
        currentBoundaryIdentity = savedBoundary
        popScope()
        currentFunction = savedFunction
        currentNya = savedNya
    }

    /**
     * Rejects a call to a function with an `emit` clause when no `try` declares its handler.
     *
     * A function with an `emit` clause may reach a handler, and the only thing that declares one is a
     * `try … { … -> }` block. Called anywhere else, a `scratch` inside it would have nowhere to go, so
     * the call is an error rather than a value that is silently always null.
     *
     * Note what is *not* claimed: the function still returns normally on the paths that do not
     * scratch, and its declared return type still describes those. The clause is about a second way
     * out, not about the absence of the first.
     *
     * There is one exception, and it is the reason [forwardsRaiseUnchanged] exists: a caller that
     * declares the *same* `emit` clause is not trying to declare a handler, it is handing the clause
     * on.
     */
    private fun checkCallableNormally(symbol: FunctionSymbol, span: Span) {
        // A `try f(...)` is exactly the construct that declares the handler, so the call it
        // wraps is the one place this requirement does not apply.
        if (resolvingTryCallee) return
        if (!symbol.canRaise) return
        if (forwardsRaiseUnchanged(symbol)) return
        diagnostics.error(
            "'${symbol.name}' declares an 'emit' clause, so it may reach a 'try' handler; call it " +
                "as 'try ${symbol.name}(...) { ... -> }' to declare the handler its 'scratch' sites " +
                "look for",
            span,
        )
    }

    /**
     * The type-argument check a construction needs, which is stricter than a call's.
     *
     * A generic value *carries* its type arguments, so the object cannot be built without knowing
     * them: the compiler writes a `Class[]` onto every instance, and a construction that named none
     * would leave it empty. Since `value::generic` is the way to read that field back, an instance
     * built without arguments would answer a question the source never asked — so the arguments are
     * required here rather than defaulted to `Object`.
     *
     * A call to a generic *function* is different: it builds nothing and keeps nothing, so it may
     * leave the arguments to inference and is checked by [checkTypeArguments] alone.
     */
    private fun checkConstructedTypeArguments(
        expression: CallExpression,
        classSymbol: ClassSymbol,
        name: String,
    ) {
        val declared = classSymbol.typeParameters
        if (declared.isEmpty()) {
            // A non-generic class takes none, so writing some is the same mistake as before.
            checkTypeArguments(expression, declared, name)
            return
        }

        // This class has the field the compiler emits for a generic declaration, so the construction
        // is the one that fills it in.
        expression.storesGenericTokens = expression.typeArguments.isNotEmpty()

        if (expression.typeArguments.isEmpty()) {
            diagnostics.error(
                "'$name' is generic, so its type arguments have to be written: " +
                    "'$name<${declared.joinToString(", ") { "?" }}>(...)'",
                expression.argumentListSpan,
            )
            return
        }
        checkTypeArguments(expression, declared, name)
        // The arguments were checked against the constructor just above, so their types are known
        // and the written arguments can now be held against them.
        checkTypeArgumentFits(expression, declared, name, expression.resolvedConstructor)
    }

    /**
     * Checks the `<...>` a call site wrote against the type parameters its callee declares.
     *
     * Neko erases type arguments, so they change nothing about the call: the descriptor is the one
     * the declaration erases to, whether or not anything was written. They are still checked,
     * because a reader who writes them means something by it. The two mistakes worth reporting are
     * the wrong number — "`Box` declares 1 type parameter (T) but 2 type arguments were provided" —
     * and an argument that names no type at all, which [resolveType] reports at the name itself.
     *
     * This is about the `<...>` on its own, as a claim about the callee. What that claim says about
     * the values the call passes is a separate question — one erasure makes easy to miss but does
     * not make meaningless — and it is [checkTypeArgumentFits] that reads them together.
     */
    private fun checkTypeArguments(expression: CallExpression, declared: List<String>, name: String) {
        val written = expression.typeArguments
        if (written.isEmpty()) return

        // Resolved once and kept, because the code generator has to name each argument as a class
        // token and must not resolve it a second way. A construction has already resolved them, to
        // build the type it produces, so only a call has any work left here.
        if (expression.resolvedTypeArguments.isEmpty()) {
            expression.resolvedTypeArguments = written.map { resolveType(it) }
        }
        if (declared.size == written.size) return

        val expected = if (declared.isEmpty()) {
            "no type parameters"
        } else {
            "${declared.size} type parameter${if (declared.size == 1) "" else "s"} " +
                declared.joinToString(", ", "(", ")")
        }
        diagnostics.error(
            "'$name' declares $expected but ${written.size} " +
                "type argument${if (written.size == 1) " was" else "s were"} provided",
            written.first().span.through(written.last().span),
        )
    }

    /**
     * Holds the type arguments a call site wrote against the values the call actually passes.
     *
     * Erasure is what makes this look unnecessary: `fun <T> id(value: T)` compiles to `id(Object)`,
     * so an `int` and a `string` are equally welcome to the JVM. That is a fact about the class
     * file, not about the source. A call that writes `id<int>("text")` has said two things that
     * cannot both be true — that `T` is `int`, and that the value handed to a `T` is a `string` —
     * and erasure is exactly why nothing downstream would notice: the value is stored as an
     * `Object` and only turns back into an `int` at a use site, by which point the failure has
     * moved a long way from the mistake.
     *
     * The check is deliberately narrow about *where* it applies. Only a parameter that is the type
     * parameter itself — `value: T` — states the argument's type outright. `value: Box<T>` says
     * only that the argument is a box of *something*, and Neko has no inference, so there is no
     * `T` to compare a written argument against and the position is passed over rather than
     * guessed at. `value: T?` is left out for a second reason: it admits `null`, whose type is
     * whatever its context says, so the two would disagree about the one value the position exists
     * to allow.
     *
     * A literal argument is passed over as well. A literal's whole nature is to take its type from
     * the place it is written, and the place it is written here has already been erased to
     * `Object`, so the type it came out with is not evidence about the type argument. Exempting it
     * is what keeps `id<byte>(1)` meaning what it looks like. Which literal belongs to which type
     * is the literal analysers' question, asked where the literal is written.
     *
     * Nothing here repeats [checkArguments]: that has already compared each argument against its
     * parameter's erased type at every site that reaches this. What is reported is the narrower
     * disagreement erasure hides — an argument that fits `Object` and does not fit the type the
     * call said `Object` stands for.
     */
    private fun checkTypeArgumentFits(
        expression: CallExpression,
        declared: List<String>,
        name: String,
        symbol: FunctionSymbol?,
    ) {
        // `null` is a callee whose parameters this pass cannot see at all, not a mismatch.
        val parameters = symbol?.declaration?.parameters ?: symbol?.constructor?.parameters ?: return

        val written = expression.typeArguments
        // Nothing written is the erased spelling, and the ordinary one: the call left the
        // arguments to inference. A wrong *number* is [checkTypeArguments]'s own complaint, and
        // pairing the lists up when they differ in length would blame an argument for it.
        if (written.isEmpty() || written.size != declared.size) return

        val writtenTypes = expression.resolvedTypeArguments
        if (writtenTypes.size != written.size) return

        parameters.forEachIndexed { index, parameter ->
            val argument = expression.arguments.getOrNull(index) ?: return@forEachIndexed
            val parameterName = bareTypeParameterOf(parameter.declaredType, declared)
                ?: return@forEachIndexed
            if (argument is IntegerLiteral || argument is CharLiteral || argument is DoubleLiteral) {
                return@forEachIndexed
            }
            val expected = writtenTypes[declared.indexOf(parameterName)]
            val actual = argument.resolvedType ?: return@forEachIndexed
            if (isAssignable(actual, expected)) return@forEachIndexed
            diagnostics.error(
                "argument ${index + 1} of '$name' is declared as $parameterName, which is " +
                    "${expected.displayName} here, but has type ${actual.displayName}",
                argument.span,
            )
        }
    }

    /**
     * The type parameter [reference] names outright, or `null` when it says something else.
     *
     * `T` is the only shape that states a parameter's type is the type argument. `T?` admits one
     * more value, and `Box<T>` or `[T] -> U` say the argument merely *contains* one — a claim a
     * written argument cannot be held to without inference deciding what is inside.
     */
    private fun bareTypeParameterOf(reference: TypeReference, declared: List<String>): String? {
        val named = reference as? NamedTypeReference ?: return null
        if (named.arguments.isNotEmpty()) return null
        return named.name.takeIf { it in declared }
    }

    /**
     * Whether a call to [callee] here may stand as an ordinary call because this function forwards
     * its `emit` clause to its own caller unchanged.
     *
     * A `scratch` reaches whichever handler matches its signature, wherever that handler was written.
     * A function that declares the same `emit [T, ...]` clause as the one it is calling has nothing to
     * do with the values: they are already in the form its own caller expects, so letting a `scratch`
     * inside the callee find the handler one frame up is exactly as correct as declaring one here.
     * Requiring a `try` would force every wrapper to declare a handler that only passes the call on.
     *
     * The clauses must match *exactly*, type for type. `emit [int]` forwarding into `emit [Object]`
     * would not be a pass-through — it would need a conversion, and a `try` is where that belongs.
     * The same holds for the result: forwarding is what carries the caller's handler through
     * unchanged, so a wrapper that names a different `R` would be promising the caller one answer
     * and handing the callee's handler to a site expecting another. Two clauses that both name no
     * result are equal in that respect —`VoidType` is a result like any other here, so a wrapper
     * over an `emit [int]` function may itself write `emit [int]` and pass the handler straight on.
     */
    private fun forwardsRaiseUnchanged(callee: FunctionSymbol): Boolean {
        val caller = currentFunction ?: return false
        if (!caller.canRaise) return false
        if (caller.emitTypes.size != callee.emitTypes.size) return false
        if (!caller.emitTypes.zip(callee.emitTypes).all { (mine, theirs) -> mine == theirs }) return false
        val mine = caller.emitResultType
        val theirs = callee.emitResultType
        // `ErrorType` is the unresolved shape; the real problem has its own diagnostic, and adding
        // "call it as a try" here would describe the same mistake twice.
        if (mine != null && theirs != null && mine != theirs && mine != ErrorType && theirs != ErrorType) {
            return false
        }
        // Recorded so the body check knows this function does reach its caller's handler, even
        // though it never writes `scratch` itself.
        forwardsRaise = true
        forwardsRaiseSymbols += caller
        // The callee's clause is doing work too: it is what the forward matched against, so a
        // function that only exists to be forwarded into is not a function with a useless clause.
        forwardedCallees += callee
        return true
    }

    /** The functions found to forward a caller's `emit` clause unchanged. */
    private val forwardsRaiseSymbols = mutableListOf<FunctionSymbol>()

    /** Functions whose `emit` clause some caller forwards unchanged. */
    private val forwardedCallees = mutableSetOf<FunctionSymbol>()

    /** Every declared function, so the end-of-run checks can revisit them. */
    private val functionsByName = LinkedHashMap<String, FunctionSymbol>()

    /**
     * Resolves the callee of a `try`, requiring a function with an `emit` clause.
     *
     * Returns the resolved symbol, or `null` after reporting why the call cannot declare a handler.
     */
    private fun resolveRaiseableCallee(call: CallExpression): FunctionSymbol? {
        val callee = call.callee
        if (callee is NameExpression) {
            val candidates = functionCandidates(callee.name, call.arguments.size)
            if (candidates.isNotEmpty()) {
                val selected = selectOverload(candidates, call)
                callee.resolution = selected
                if (!selected.canRaise) {
                    diagnostics.error(
                        "'${selected.name}' does not declare an 'emit' clause, so it never " +
                            "scratches and this 'try' has nothing to declare a handler for",
                        callee.span,
                    )
                }
                return selected
            }
            if (functionNamed(callee.name).isNotEmpty()) {
                val arities = functionNamed(callee.name)
                    .map { it.parameterTypes.size }
                    .distinct()
                    .sorted()
                    .joinToString(" or ")
                val count = call.arguments.size
                diagnostics.error(
                    "function '${callee.name}' expects $arities argument(s) but $count " +
                        "${if (count == 1) "was" else "were"} provided",
                    callee.span,
                )
                return null
            }
            diagnostics.error(
                "'try' needs a call to a function with an 'emit' clause, but there is no function " +
                    "named '${callee.name}'",
                callee.span,
            )
            return null
        }

        if (callee is MemberAccessExpression) {
            // Reuse the ordinary member-call path so a bad `try` callee reports the same reasons a
            // bad call would, then check the extra requirement that it declares an `emit` clause.
            val type = analyzeMemberCall(call, callee)
            val resolved = callee.resolvedFunction
            if (resolved == null || type.isError) return null
            if (!resolved.canRaise) {
                diagnostics.error(
                    "'${resolved.name}' does not declare an 'emit' clause, so it never scratches " +
                        "and this 'try' has nothing to declare a handler for",
                    callee.memberSpan,
                )
            }
            return resolved
        }

        // A function *value* has no declaration to read an `emit` clause from, so it can never be the
        // callee of a `try`. Refer to the declaration by name instead.
        analyzeExpression(callee)
        diagnostics.error(
            "'try' needs a call to a declared function, because only a declaration records the " +
                "values its 'scratch' sites pass",
            callee.span,
        )
        return null
    }

    /**
     * `str: string = "Hell world!"` — and the rejected `str: string = "new value"`.
     *
     * The duplicate check happens *before* the initializer is analysed, but the new symbol is only
     * introduced *after* it. That ordering is what makes `lambda: int = lambda()(1, 2)` resolve the
     * call to the function `lambda` while still letting a genuine redeclaration be reported.
     */
    private fun analyzeVariableDeclaration(statement: VariableDeclarationStatement) {
        val declaredType = resolveType(statement.declaredType)
        statement.resolvedType = declaredType

        val existing = lookup(statement.name)?.takeIf { it.isDeclaration() }
        if (existing != null) {
            diagnostics.error(
                "variable '${statement.name}' is already declared in this scope",
                statement.nameSpan,
                notes = listOf(
                    diagnostics.note(
                        "previous declaration of '${statement.name}' is here",
                        existing.span,
                    ),
                ),
            )
        }

        val initializer = statement.initializer
        if (initializer == null) {
            if (!declaredType.hasImplicitDefault) {
                diagnostics.error(
                    "variable '${statement.name}' of type ${declaredType.displayName} must be " +
                        "initialized when it is declared",
                    statement.nameSpan,
                )
            }
        } else {
            val valueType = analyzeExpression(initializer, declaredType)
            if (!isAssignable(valueType, declaredType)) {
                diagnostics.error(
                    "variable '${statement.name}' is declared as ${declaredType.displayName} but its " +
                        "initializer has type ${valueType.displayName}",
                    initializer.span,
                )
            }
        }

        // The duplicate is reported, but the initializer is still analysed so that an unrelated
        // mistake inside it is found in the same run. The new symbol, however, is *not* introduced
        // when the name was already taken — otherwise the original declaration would be shadowed and
        // every later use of the name would silently refer to the rejected one.
        if (existing == null) {
            val symbol = LocalSymbol(
                name = statement.name,
                type = declaredType,
                span = statement.nameSpan,
                kind = statement.kind,
                enclosingLambda = currentBoundaryIdentity,
            )
            statement.symbol = symbol
            scopes.last().symbols[statement.name] = symbol
        }
    }

    /**
     * `str = "new value"` — the statement the sample marks as *successful*, which pins down the
     * rule: the target must already be declared, and must be mutable.
     */
    private fun analyzeAssignment(statement: AssignmentStatement) {
        if (statement.target is MemberAccessExpression) {
            analyzeFieldAssignment(statement, statement.target)
            return
        }

        val target = statement.target as? NameExpression ?: run {
            analyzeExpression(statement.target)
            analyzeExpression(statement.value)
            diagnostics.error(
                "the left-hand side of an assignment must be a variable name or a field access",
                statement.target.span,
            )
            return
        }

        // Nothing is in scope under this name, so the statement declares it and its type comes from
        // the value: `instance = Instance()`. Neko spells assignment the same way -- the sample's
        // `str = "new value"` is valid because `str` *is* in scope by then -- and only the scope
        // separates the two. This is checked before resolving so that a name being declared is not
        // first reported as unresolved.
        if (lookup(target.name) == null) {
            declareWithInferredType(statement, target)
            return
        }

        val resolved = resolveName(target) ?: return

        if (resolved !is LocalSymbol && resolved !is FieldSymbol) {
            diagnostics.error(
                "'${resolved.name}' is a ${resolved.describeKind()} and cannot be assigned to",
                target.span,
            )
            analyzeExpression(statement.value)
            return
        }

        if (!resolved.isAssignableDeclaration) {
            diagnostics.error(
                "cannot assign to ${resolved.describeKind()} '${resolved.name}' because it is " +
                    "declared with 'val'",
                target.span,
                notes = listOf(diagnostics.note("'${resolved.name}' is declared here", resolved.span)),
            )
        }
        // Capture is by value, so writing to a variable from an enclosing scope would silently have
        // no effect on the enclosing variable. Reject it instead of miscompiling it. A `try` handler
        // captures for the same reason a lambda does, so the same rule covers it.
        if (resolved is LocalSymbol && currentBoundaryIdentity != null &&
            resolved.enclosingLambda !== currentBoundaryIdentity
        ) {
            val where = if (currentLambda != null) "a lambda" else "a 'try' handler"
            diagnostics.error(
                "cannot assign to '${resolved.name}' inside $where: it is declared in the " +
                    "enclosing scope and the block captures variables by value",
                target.span,
                notes = listOf(diagnostics.note("'${resolved.name}' is declared here", resolved.span)),
            )
        }

        val valueType = analyzeExpression(statement.value, resolved.type)
        // The write lands after the value is read, so the value may still lean on the old fact — but
        // from here on the name holds something else, and what was proved about the old value says
        // nothing about the new one.
        forgetNarrowingsOf(resolved)
        if (!isAssignable(valueType, resolved.type)) {
            diagnostics.error(
                "cannot assign a value of type ${valueType.displayName} to '${resolved.name}' of " +
                    "type ${resolved.type.displayName}",
                statement.value.span,
            )
        }
    }

    /**
     * `instance = Instance()`, where nothing was in scope under that name — a declaration whose type
     * is inferred from the value.
     *
     * This is what lets a reader write the name once instead of twice. The type is the value's own
     * type rather than anything inferred from later use, which is the simple and predictable rule:
     * Neko's analyzer works in one pass, so at this point there is nothing later to consult.
     *
     * The local is mutable. A bare assignment is the spelling for writing to a name, so a reader
     * who wants a `val` still says so, and the desugaring cannot silently take that choice away.
     *
     * The name is deliberately *not* registered before the value is analysed: `count = count + 1`
     * with no `count` in scope yet is a mistake, and reporting the reference inside the value is a
     * better answer than quietly declaring a variable that starts from itself.
     */
    private fun declareWithInferredType(statement: AssignmentStatement, target: NameExpression) {
        val valueType = analyzeExpression(statement.value)

        val symbol = LocalSymbol(
            name = target.name,
            type = valueType,
            span = target.span,
            kind = VariableKind.VAR,
            enclosingLambda = currentBoundaryIdentity,
        )
        statement.declaredLocal = symbol
        target.resolution = symbol
        target.resolvedType = valueType
        scopes.last().symbols[target.name] = symbol
    }

    /**
     * `obj.field = value` — writes to an instance field through its receiver.
     *
     * The receiver is analysed first, so the object exists before anything is stored into it, and the
     * member has to be a mutable field of that object's class.
     */
    private fun analyzeFieldAssignment(statement: AssignmentStatement, target: MemberAccessExpression) {
        // `Cls.field = value` writes a static field by class name, which needs no object — the same
        // spelling `analyzeMemberAccess` accepts for reading one. It is matched before the receiver
        // is analysed, because a class name is not a value and has no type of its own.
        val className = (target.receiver as? NameExpression)?.name
        val namedClass = className?.let { classNamed(it, target.memberSpan) }
        if (namedClass != null) {
            target.receiverIsClass = true
            target.receiver.resolvedType = ClassType(namedClass.jvmInternalName, namedClass.name)
            assignToField(statement, target, namedClass, className, viaClass = true)
            return
        }

        val receiverType = analyzeExpression(target.receiver)

        if (receiverType.isError) {
            analyzeExpression(statement.value)
            return
        }
        // Writing a field dereferences the receiver just as reading one does, and there is no safe
        // spelling of it: `?.` guards a *read*, so it cannot be suggested here. A test is the one
        // thing that does help, and narrowing is what makes it help.
        if (receiverType is NullableType) {
            analyzeExpression(statement.value)
            diagnostics.error(
                "cannot assign to member '${target.memberName}' on a value of type " +
                    "${receiverType.displayName}, which may be null; there is no safe spelling of " +
                    "a write, so test it with '!= null' first",
                target.memberSpan,
            )
            return
        }
        if (receiverType !is ClassType) {
            analyzeExpression(statement.value)
            diagnostics.error(
                "cannot assign to member '${target.memberName}' on a value of type " +
                    "${receiverType.displayName}, which has no members",
                target.memberSpan,
            )
            return
        }

        val classSymbol = classOf(receiverType)
        if (classSymbol == null) {
            analyzeExpression(statement.value)
            // A class from another file: the analyzer cannot see its fields, and saying so is more
            // useful than claiming the field does not exist.
            diagnostics.error(
                "cannot assign to '${target.memberName}': ${receiverType.displayName} is not " +
                    "declared in this file, so its fields cannot be checked",
                target.memberSpan,
            )
            return
        }

        assignToField(statement, target, classSymbol, receiverType.displayName)
    }

    /**
     * Checks the field half of `Recv.field = value` and records the field for code generation.
     *
     * [viaClass] is true for `Cls.field = value`. Such a write can only reach a `static` field: an
     * instance one needs an object even when its class is named explicitly.
     */
    private fun assignToField(
        statement: AssignmentStatement,
        target: MemberAccessExpression,
        classSymbol: ClassSymbol,
        receiverName: String,
        viaClass: Boolean = false,
    ) {
        val field = classSymbol.fields[target.memberName]
        if (field == null) {
            analyzeExpression(statement.value)
            diagnostics.error(
                "$receiverName has no field named '${target.memberName}'",
                target.memberSpan,
            )
            return
        }

        if (viaClass && !field.isStatic) {
            diagnostics.error(
                "field '${field.name}' is an instance member of $receiverName, so it cannot be " +
                    "assigned from the class; create an object first and write 'obj.${field.name}'",
                target.memberSpan,
            )
        } else if (!viaClass && field.isStatic) {
            diagnostics.error(
                "field '${field.name}' is a static member of $receiverName; write " +
                    "'$receiverName.${field.name} = ...' to assign it without an object",
                target.memberSpan,
            )
        }

        target.resolvedField = field
        target.resolvedType = field.type

        // A `val` field is immutable once the object exists, so the only legal place to write it is
        // the constructor that is building the object. Anywhere else the write is an error.
        if (!field.isMutable && !inConstructorAssignment(field)) {
            diagnostics.error(
                "cannot assign to field '${field.name}' because it is declared with 'val'",
                target.memberSpan,
                notes = listOf(diagnostics.note("'${field.name}' is declared here", field.span)),
            )
        }

        val valueType = analyzeExpression(statement.value, field.type)
        // The field holds something new from here on, so every fact about it — the one about the
        // field itself and the ones about it reached through particular values — is now about a
        // value that is gone.
        forgetNarrowingsOfField(field)
        if (!isAssignable(valueType, field.type)) {
            diagnostics.error(
                "cannot assign a value of type ${valueType.displayName} to '${field.name}' of " +
                    "type ${field.type.displayName}",
                statement.value.span,
            )
        }
    }

    /**
     * Whether the assignment being analysed is the constructor's own write to its class' [field].
     *
     * Both spellings count: `this.data = ...` and a bare `data = ...` inside the constructor are the
     * same write, and the standard library uses the explicit one. A write to another class' field is
     * never allowed, so the enclosing class has to match.
     */
    private fun inConstructorAssignment(field: FieldSymbol): Boolean {
        val function = currentFunction ?: return false
        if (!function.isConstructor) return false
        return function.enclosingClass === field.enclosingClass
    }

    private fun analyzeReturn(statement: ReturnStatement) {
        val function = currentFunction
        val extractor = currentExtractor
        if (extractor != null) {
            // The extractor body is emitted inside the class the compiler generates for it, so a
            // `return` here would leave that class' `invoke`, not the enclosing function — and the
            // value it names is checked against a return type it does not belong to. Analysing the
            // value without an expectation is all that is left to do.
            diagnostics.error(
                if (extractor.acceptsYield) {
                    "'return' cannot be used inside a 'try' handler; use 'yield' to produce the " +
                        "value of the 'try' expression, or 'nyan' to answer its 'scratch' site"
                } else {
                    "'return' cannot be used inside a 'try' handler; the block is compiled into a " +
                        "method of its own, so there is no function here to return from — write " +
                        "'nyan' to answer the 'scratch' site"
                },
                statement.keywordSpan,
            )
            statement.value?.let { analyzeExpression(it) }
            return
        }

        if (currentLambda != null) {
            diagnostics.error(
                "'return' cannot be used inside a lambda; use 'yield' to produce the lambda's value",
                statement.keywordSpan,
            )
        } else if (function == null) {
            diagnostics.error("'return' can only be used inside a function", statement.keywordSpan)
        }

        val declared = function?.returnType ?: ErrorType
        val value = statement.value

        if (value == null) {
            if (!declared.isVoidOrError) {
                diagnostics.error(
                    "function '${function?.name}' must return a value of type ${declared.displayName}",
                    statement.keywordSpan,
                )
            }
            return
        }

        val valueType = analyzeExpression(value, declared)
        when {
            declared is VoidType -> diagnostics.error(
                "function '${function?.name}' declares no return type, so 'return' must not " +
                    "produce a value",
                value.span,
            )

            !isAssignable(valueType, declared) -> diagnostics.error(
                "function '${function?.name}' returns ${declared.displayName} but this 'return' " +
                    "produces ${valueType.displayName}",
                value.span,
            )
        }
    }

    /**
     * `yield x + y` — a departure with a value attached.
     *
     * Four homes accept it, and they are worth telling apart because they leave by different routes:
     *
     *  * **A function with an `emit` clause.** This is the one the sample exercises: `yield` throws
     *    `EmitYieldException` carrying the value, and the `try` wrapping the call catches it and keeps
     *    the value. It is how a function that cannot answer through `nyan` — because it never wrote a
     *    `scratch` — still hands something back.
     *  * **A lambda.** The value returns from the generated `invoke`.
     *  * **A `try` extractor.** The value becomes the `try` expression's own value.
     *  * **A `nya` used as a value.** The value becomes that branch's result.
     *
     * In all four the first `yield` fixes the result type that later ones must match.
     */
    private fun analyzeYield(statement: YieldStatement) {
        val lambda = currentLambda
        val extractor = currentExtractor
        val nya = currentNya
        // A function whose `emit` clause lets it depart: `yield` is exactly how it does so.
        val emitFunction = currentFunction?.takeIf { it.canRaise }

        if (lambda == null && extractor == null && nya == null && emitFunction == null) {
            diagnostics.error(
                "'yield' can only be used inside a lambda, a 'try', a 'nya' used as a value, or a " +
                    "function that declares an 'emit' clause; use 'return' to return from a function",
                statement.keywordSpan,
            )
        }

        if (extractor != null && !extractor.acceptsYield) {
            // `try f(...) { a -> yield 1 }` in statement position is worth nothing, so a `yield` with
            // a value there would be a value nobody could ever read. A *bare* `yield` is a different
            // matter: it carries nothing, so it is exactly how a handler abandons a call whose `try`
            // wanted no answer.
            if (statement.value != null) {
                diagnostics.error(
                    "a 'try' statement is worth nothing, so its extractor cannot 'yield' a value; " +
                        "write a bare 'yield' to leave it, or 'result: T = try ...' to keep the value",
                    statement.keywordSpan,
                )
            }
        }

        // A bare `yield` carries nothing, so there is no expression to analyze and no result type to
        // fix. It is accepted precisely where the construct it leaves is worth nothing, which is
        // checked below.
        val value = statement.value
        if (value == null) {
            // `v: int = try f() { a -> yield }` — the construct is worth a value, so a `yield` that
            // produces none leaves one path of it without an answer. A bare `yield` in a `try`
            // statement, or in a function with an `emit` clause, is fine: those are worth nothing,
            // and this is exactly where the `yield` says so.
            if (nya != null ||
                (extractor != null && extractor.acceptsYield) ||
                (lambda != null && lambda.expectedReturnType != null)
            ) {
                diagnostics.error(
                    "'yield' must be followed by the value the enclosing ${constructName(nya, extractor, lambda)} " +
                        "produces, since that construct is worth one; leave it out only where nothing " +
                        "reads it",
                    statement.keywordSpan,
                )
            }
            return
        }

        // A `try` extractor takes precedence over a lambda, and a `nya` value form takes precedence
        // over both: a `yield` inside one belongs to the innermost construct that is waiting for a
        // value, which is the one whose block the statement is written in.
        val expected = nya?.expected ?: extractor?.yieldedType ?: lambda?.expectedReturnType
        val valueType = analyzeExpression(value, expected)

        when {
            nya != null -> {
                if (nya.inFailureBlock) nya.failureYielded = true else nya.elseYielded = true
                val alreadyYielded = nya.expression.yieldedType
                if (alreadyYielded == null) {
                    nya.expression.yieldedType = valueType // The first `yield` fixes the result type.
                } else if (!isAssignable(valueType, alreadyYielded)) {
                    diagnostics.error(
                        "this 'yield' produces ${valueType.displayName} but the other block of the " +
                            "same 'nya' produces ${alreadyYielded.displayName}",
                        value.span,
                    )
                }
            }

            extractor != null && extractor.acceptsYield -> {
                val alreadyYielded = extractor.yieldedType
                if (alreadyYielded == null) {
                    extractor.yieldedType = valueType // The first `yield` fixes the result type.
                } else if (!isAssignable(valueType, alreadyYielded)) {
                    diagnostics.error(
                        "this 'yield' produces ${valueType.displayName} but an earlier 'yield' in " +
                            "the same 'try' extractor produces ${alreadyYielded.displayName}",
                        value.span,
                    )
                }
            }

            lambda != null -> {
                if (expected == null) {
                    lambda.expectedReturnType = valueType // The first `yield` fixes the result type.
                } else if (!isAssignable(valueType, expected)) {
                    // The type being violated may come from an earlier `yield` or from the function
                    // type the lambda is being used as, and the two are worth telling apart: one is a
                    // disagreement inside this lambda, the other a disagreement with its destination.
                    diagnostics.error(
                        if (lambda.returnTypeIsDeclared) {
                            "this 'yield' produces ${valueType.displayName} but the function type " +
                                "this lambda is used as returns ${expected.displayName}"
                        } else {
                            "this 'yield' produces ${valueType.displayName} but an earlier 'yield' " +
                                "in the same lambda produces ${expected.displayName}"
                        },
                        value.span,
                    )
                }
            }
        }
    }

    /** Names the construct a `yield` answers, for a diagnostic about a value it left out. */
    private fun constructName(
        nya: NyaValue?,
        extractor: HandlerOwner?,
        lambda: LambdaState?,
    ): String = when {
        nya != null -> "'nya'"
        extractor is BlepDeclaration -> "'blep' branch"
        extractor != null -> "'try'"
        lambda != null -> "lambda"
        else -> "construct"
    }

    private fun analyzeIf(statement: IfStatement) {
        requireBool(analyzeExpression(statement.condition), statement.condition.span, "if")
        val incoming = saveNarrowings()
        // Each arm is analysed with what the condition proves *for that arm*, and the facts are put
        // back afterwards: an `if` may add to what is known inside its arms, never outside them, so
        // nothing is known after the `if` unless an arm left the flow (see [factsAfterExit]).
        applyFacts(factsTrue(statement.condition))
        analyzeStatement(statement.thenBranch)
        restoreNarrowings(incoming)
        statement.elseBranch?.let {
            applyFacts(factsFalse(statement.condition))
            analyzeStatement(it)
            restoreNarrowings(incoming)
        }
    }

    private fun analyzeWhile(statement: WhileStatement) {
        requireBool(analyzeExpression(statement.condition), statement.condition.span, "while")
        // The body runs only while the condition holds, so it may lean on the test — but the loop
        // may also leave through its own condition having failed, and a `var` written in the body
        // must not keep a fact proved before the loop, so the facts are dropped at the end rather
        // than joined back in.
        val incoming = saveNarrowings()
        applyFacts(factsTrue(statement.condition))
        analyzeStatement(statement.body)
        restoreNarrowings(incoming)
    }

    /**
     * `for (i: int = 0; i < 100; i++) body`
     *
     * The loop gets a scope of its own, which is what makes the loop variable private to it: `i` is
     * declared before the condition is analysed, so the condition, the update and the body all see it,
     * and nothing sees it afterwards. That is the same rule Java's `for` follows, and it is what lets
     * two loops in one method both declare `i`.
     *
     * Every part is optional. A missing condition means "always", so there is no test to require a
     * `bool` of — `for (;;)` is an endless loop, and the only way out is a `return` or a `yield`.
     */
    private fun analyzeFor(statement: ForStatement) {
        pushScope(statement)
        try {
            // The initializer declares the loop variable, so it is analysed first: everything after it
            // may refer to that variable.
            statement.initializer?.let { analyzeVariableDeclaration(it) }

            val condition = statement.condition
            if (condition != null) {
                requireBool(analyzeExpression(condition), condition.span, "for")
            }

            // The body may lean on a condition that has not failed yet, exactly as a `while` body can.
            val incoming = saveNarrowings()
            if (condition != null) applyFacts(factsTrue(condition))

            // The update runs after the body and before the next test. It is analysed inside the
            // body's accumulated facts, because it can only ever run after a pass that passed the
            // test — and any assignment in it is picked up where it lands.
            statement.body.let { analyzeStatement(it) }
            statement.update?.let { analyzeStatement(it) }

            restoreNarrowings(incoming)
        } finally {
            popScope()
        }
    }

    /**
     * `for (element: int in list) body`
     *
     * Two things may be iterated: an array, and a type implementing the standard library's
     * `Iterable<T>`. The array case needs no interface because the JVM has no array interface to
     * implement, so it is a separate rule rather than a special case of the other one.
     *
     * The element variable is introduced as a **read-only** local, which is what makes an assignment
     * to it an error rather than a no-op: the next pass overwrites it, so the stored value would be
     * computed and thrown away.
     */
    private fun analyzeForEach(statement: ForEachStatement) {
        val declaredType = resolveType(statement.elementType)
        statement.resolvedElementType = declaredType

        val iterableType = analyzeExpression(statement.iterable)
        statement.iterableType = iterableType

        // The element type the iterable yields, or `null` after reporting that it yields nothing.
        // An iterable's `next()` answers `Object`, so there is nothing to check the declared type
        // against: the compiler unboxes or casts into whatever the loop declared, and a mismatch is a
        // `ClassCastException` at run time — the same promise a cast makes anywhere else.
        val produced = elementTypeOf(iterableType, statement.iterable.span) ?: return

        if (produced !== ObjectType && declaredType !== ErrorType && produced !== ErrorType &&
            !isAssignable(produced, declaredType)
        ) {
            diagnostics.error(
                "the elements of this ${iterableType.displayName} have type " +
                    "${produced.displayName}, which cannot be bound to '${statement.name}' of type " +
                    declaredType.displayName,
                statement.nameSpan,
            )
        }

        pushScope(statement)
        try {
            val symbol = LocalSymbol(
                name = statement.name,
                type = declaredType,
                span = statement.nameSpan,
                // Read-only, so an assignment to it is refused by the same check that refuses one to a
                // `val`.
                kind = VariableKind.VAL,
                enclosingLambda = currentBoundaryIdentity,
            )
            statement.symbol = symbol
            scopes.last().symbols[statement.name] = symbol
            analyzeStatement(statement.body)
        } finally {
            popScope()
        }
    }

    /**
     * The element type [type] yields when iterated, or `null` after reporting that it yields nothing.
     *
     * An array answers its component type, which is a genuine check: `for (e: string in ints)` is a
     * mistake the compiler can see. An iterable answers `Object`, because that is what its `next()`
     * declares — the loop variable's own type is then the promise, checked by the cast the compiler
     * emits. Anything else is an error: `for (e: int in 5)` has no elements, and saying so here is more
     * useful than letting the code generator emit a call to a method that is not there.
     */
    private fun elementTypeOf(type: NekoType, span: Span): NekoType? {
        if (type === ErrorType) return ErrorType
        if (type is ArrayType) return type.element

        val classType = type as? ClassType
        if (classType != null && iterableSupertypeOf(classType) != null) return ObjectType

        diagnostics.error(
            "${type.displayName} has no elements to iterate: 'for ... in' takes an array or a type " +
                "that implements Iterable",
            span,
        )
        return null
    }

    /**
     * The `Iterable<...>` a class implements, or the class itself when it *is* one, or `null`.
     *
     * Walks both the superclass and the interfaces, transitively, because a class may reach `Iterable`
     * indirectly — `interface Named: Iterable<string>` is a perfectly ordinary way to say it, and a
     * direct-implementation check would report it as not iterable. [ClassType] carries no symbol of
     * its own, so the walk goes through [classes], which is what the analyzer keeps for
     * exactly this sort of question.
     */
    private fun iterableSupertypeOf(type: ClassType, seen: MutableSet<String> = mutableSetOf()): ClassType? {
        if (type.internalName == ITERABLE_INTERFACE) return type
        if (!seen.add(type.internalName)) return null

        val symbol = classes[type.internalName] ?: return null
        // A class's own type is the one carrying the type arguments; `Iterable` reached through the
        // superclass chain keeps whichever arguments were written along the way.
        for (interfaceType in symbol.interfaces) {
            iterableSupertypeOf(interfaceType, seen)?.let { return it }
        }
        symbol.superclass?.let { superclassType ->
            iterableSupertypeOf(superclassType, seen)?.let { return it }
        }
        return null
    }

    /**
     * `i++` — checks that the target is a numeric variable that may be written.
     *
     * Only `int`, `long`, `short` and `byte` are accepted: there is no `++` for a `double`, matching the
     * fact that this language has no `double` arithmetic that would keep a whole number whole. A `val`
     * and a for-each element are refused for the same reason an assignment to them is.
     */
    private fun analyzeIncrement(statement: IncrementStatement) {
        // `holder.count++` — the target is a field, reached through the member access that was kept
        // beside it. Analysing that access is what resolves the field and checks the receiver, so it
        // is done first and the field's own type decides everything else.
        val member = statement.memberTarget
        if (member != null) {
            analyzeExpression(member)
            statement.target.resolution = member.resolvedField
            val fieldType = member.resolvedField?.type ?: return
            statement.target.resolvedType = fieldType
            if (!isIncrementable(fieldType)) {
                diagnostics.error(
                    "'${statement.operator}' needs a whole-number field, but " +
                        "'${member.memberName}' is of type ${fieldType.displayName}",
                    statement.operatorSpan,
                )
            }
            return
        }

        val target = statement.target
        val symbol = lookup(target.name)
        if (symbol == null) {
            diagnostics.error(
                "unresolved reference '${target.name}'",
                target.nameSpan,
                unresolved = true,
            )
            return
        }
        target.resolution = symbol

        val type = symbol.type
        target.resolvedType = type

        if (!isIncrementable(type)) {
            diagnostics.error(
                "'${statement.operator}' needs a whole-number variable, but '${target.name}' is " +
                    "of type ${type.displayName}",
                statement.operatorSpan,
            )
            return
        }

        if (symbol is LocalSymbol && symbol.kind == VariableKind.VAL) {
            diagnostics.error(
                "'${target.name}' is read-only, so '${statement.operator}' cannot change it",
                target.nameSpan,
                notes = listOf(diagnostics.note("'${target.name}' is declared here", symbol.span)),
            )
        }
    }

    /** Whether `++`/`--` may be applied to a value of [type]. */
    private fun isIncrementable(type: NekoType): Boolean =
        type === IntType || type === LongType || type === ShortType || type === ByteType

    /**
     * `when (a) { 3 -> ...; 2 -> { ... } }`
     *
     * Every branch compares its value against the subject, so each value must be comparable with the
     * subject's type. A branch body opens its own scope, exactly as an `if` body does, so a variable
     * declared in one arm is invisible in the next.
     */
    private fun analyzeWhen(statement: WhenStatement) {
        val subjectType = analyzeExpression(statement.subject)
        var seenElse = false

        for (branch in statement.branches) {
            val condition = branch.condition
            if (condition == null) {
                if (seenElse) {
                    diagnostics.error(
                        "this 'when' already has an 'else' branch; only the last branch may be 'else'",
                        branch.span,
                    )
                }
                seenElse = true
            } else {
                if (seenElse) {
                    diagnostics.error(
                        "'else' must be the last branch of a 'when'",
                        condition.span,
                    )
                }
                val valueType = analyzeExpression(condition, subjectType)
                if (!subjectType.isError && !valueType.isError && !isAssignable(valueType, subjectType)) {
                    diagnostics.error(
                        "this 'when' branch tests a value of type ${valueType.displayName} but the " +
                            "subject has type ${subjectType.displayName}",
                        condition.span,
                    )
                }
            }

            analyzeStatement(branch.body)
        }
    }

    /**
     * Loads `java.lang.[simpleName]` without running its static initializer, or `null` when it does
     * not exist.
     *
     * Initialization is deliberately skipped: the compiler must not execute code from the program
     * being compiled, and a class' identity is all that is being asked about here.
     */
    private fun loadPlatformClass(simpleName: String): Class<*>? = loadQualifiedClass("java.lang.$simpleName")

    /**
     * The class the JVM loads by its fully-qualified name, or `null` when there is none.
     *
     * `loadPlatformClass` answers only for `java.lang`, which is what an *unqualified* name is allowed
     * to reach. A dotted name says where it expects the class to live, so it is loaded as written —
     * that is what makes `import java.util.HashMap` answerable, and it is also what makes a typo in a
     * class import visible instead of being accepted as a package called `java.util.Hashmap`.
     */
    private fun loadQualifiedClass(binaryName: String): Class<*>? = try {
        Class.forName(binaryName, false, javaClass.classLoader)
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        null
    }

    /**
     * Whether the platform class named [jvmInternalName] is, or descends from, `java.lang.Throwable`.
     *
     * The program's own classes are walked through their symbols, but a chain that leaves the program
     * — `EmitYieldException: Exception()` names `java/lang/Exception`, which the compiler does not
     * compile — can only be read from the platform. Asking the JDK keeps the answer correct for any
     * base class the standard library might pick (`RuntimeException`, `IllegalStateException`, …)
     * instead of special-casing `Exception`.
     *
     * A name the JVM cannot load answers `false`: nothing can be said about it, and guessing would be
     * worse than the refusal.
     */
    private fun isThrowableClass(jvmInternalName: String): Boolean {
        val loaded = loadQualifiedClass(jvmInternalName.replace('/', '.')) ?: return false
        return java.lang.Throwable::class.java.isAssignableFrom(loaded)
    }

    /** Whether a value of type [sub] is acceptable where [supertype] is expected. */
    private fun isSubtype(sub: NekoType, supertype: NekoType): Boolean {
        if (sub.isError || supertype.isError) return false
        if (sub == supertype) return true
        // `Throwable` is a type from outside the program, so the JDK side of the hierarchy cannot be
        // walked. What *can* be walked is the program's own side: a class it declares has a symbol,
        // and that symbol knows whether `Exception` is among its ancestors. So a declared class fits
        // an explicitly `Throwable`-typed slot exactly when it descends from `java/lang/Throwable` —
        // which is what lets `EmitYieldException: Exception()` satisfy a `Throwable` return type
        // instead of being refused for not being `Throwable` itself.
        if (supertype === ThrowableType) {
            if (sub === ThrowableType) return true
            val thrown = (sub as? ClassType) ?: return false
            val thrownSymbol = classes[thrown.internalName] ?: return false
            // Either the program walked all the way to `Throwable` itself, or it stopped at a JDK
            // class that is itself a `Throwable` — `EmitYieldException: Exception()` names
            // `java/lang/Exception`, which the program does not compile, so the chain above it has to
            // be read from the platform rather than from symbols.
            if (thrownSymbol.descendsFrom("java/lang/Throwable")) return true
            val immediate = thrownSymbol.superclass?.internalName ?: return false
            return isThrowableClass(immediate)
        }

        val subClass = sub as? ClassType ?: return false
        val superClass = supertype as? ClassType ?: return false
        val subSymbol = classes[subClass.internalName] ?: return false

        // The supertype may be a platform class the program never declares — `java.util.Iterator` is
        // what `class Cursor: Iterator` names, and there is no `ClassSymbol` for it because the
        // program does not compile the JDK. Its internal name is all there is to compare against, so
        // the declared-supertypes walk in `descendsFrom` answers instead of `isSubtypeOf`. Without
        // this, implementing a JDK interface would satisfy the `for ... in` check and then fail the
        // assignability check on the very `iterator()` method that makes it work.
        val superSymbol = classes[superClass.internalName]
        return if (superSymbol != null) {
            subSymbol.isSubtypeOf(superSymbol)
        } else {
            subSymbol.descendsFrom(superClass.internalName)
        }
    }

    /**
     * Whether a value of type [actual] may be used where [expected] is expected.
     *
     * This is [NekoType.isAssignableTo] plus the one thing only the analyzer can answer: whether a
     * *declared* subtype is acceptable. `s: Shape = Square(4)` is legal because `Square` implements
     * `Shape`, and that fact lives in the class symbols, not in the two types, which are `ClassType`s
     * that know nothing of each other. A class the analyzer cannot see — one from another file or
     * from the platform — falls back to the type-level rule.
     */
    private fun isAssignable(actual: NekoType, expected: NekoType): Boolean {
        if (actual.isAssignableTo(expected)) return true
        // Two uses of one class that name different type arguments are already settled — false — and
        // the hierarchy below must not overrule it. `isSubtype` answers "is this class a kind of that
        // one", and for the same class the answer is trivially yes; that is exactly why the arguments
        // have to be checked before the question is asked rather than after.
        if (!actual.agreesOnTypeArguments(expected)) return false
        // A nullable value never fits a non-nullable type. That direction is the whole point of the
        // suffix, so it is refused before the class hierarchy is consulted — otherwise a declared
        // subtype would let `Cat?` through where a `Cat` is expected.
        if (actual is NullableType && expected !is NullableType) return false
        // Both sides non-nullable, or both nullable: the *inner* types decide, because a declared
        // subtype fits its supertype for a reason that lives in the class symbols rather than in the
        // two types. That is what makes `Square` assignable to `Shape?`.
        val source = if (actual is NullableType) actual.inner else actual
        val target = if (expected is NullableType) expected.inner else expected
        return isSubtype(source, target)
    }

    /**
     * A class type as the source wrote it, with its type arguments in the name.
     *
     * `displayName` is what diagnostics print, and a message about a mismatch has to show what
     * differs: `declared as ArrayList<int> but its initializer has type ArrayList<string>`. Left bare,
     * the same sentence would name one type twice and read as if the compiler had contradicted itself.
     */
    private fun parameterisedType(bare: ClassType, arguments: List<NekoType>): ClassType {
        if (arguments.isEmpty()) return bare
        return ClassType(
            bare.internalName,
            "${bare.displayName}<${arguments.joinToString(", ") { it.displayName }}>",
            arguments,
        )
    }

    private fun requireBool(type: NekoType, span: Span, construct: String) {
        if (type.isError || type == BoolType) return
        diagnostics.error(
            "the condition of '$construct' must be bool but has type ${type.displayName}",
            span,
        )
    }

    // ------------------------------------------------------------- expressions

    /**
     * Analyses an expression, records its type on the node, and returns it.
     *
     * [expectedType] is threaded through so a lambda literal can infer its parameter types from the
     * context it appears in.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun analyzeExpression(expression: Expression, expectedType: NekoType? = null): NekoType {
        val type = computeType(expression, expectedType)
        expression.resolvedType = type
        return type
    }

    private fun computeType(expression: Expression, expectedType: NekoType?): NekoType = when (expression) {
        is IntegerLiteral -> analyzeIntegerLiteral(expression, expectedType)
        is DoubleLiteral -> analyzeDoubleLiteral(expression, expectedType)
        is CharLiteral -> analyzeCharLiteral(expression, expectedType)
        is StringLiteral -> StringType
        is BoolLiteral -> BoolType
        is NullLiteral -> analyzeNullLiteral(expression, expectedType)
        is NameExpression -> readTypeOf(resolveName(expression))
        is ThisExpression -> analyzeThis(expression)
        is BinaryExpression -> analyzeBinary(expression, expectedType)
        is ChainedComparisonExpression -> analyzeChainedComparison(expression)
        is UnaryExpression -> analyzeUnary(expression, expectedType)
        is CallExpression -> analyzeCall(expression, expectedType)
        is LambdaExpression -> analyzeLambda(expression, expectedType)
        is MemberAccessExpression -> analyzeMemberAccess(expression)
        is GenericAccessExpression -> analyzeGenericAccess(expression)
        is IndexExpression -> analyzeIndexRead(expression)
        is NyaExpression -> analyzeNyaExpression(expression, expectedType)
        is TryExpression -> analyzeTryExpression(expression)
        is TryScratchExpression -> analyzeTryScratch(expression, expectedType)
        is ScratchExpression -> analyzeScratch(expression, expectedType)
    }

    // ------------------------------------------------------------------ literals

    /**
     * The type of `null`, which is whichever nullable type its context asks for.
     *
     * `null` is the one expression in the language that is not a value *of* a type: it is the absence
     * of one, and the only thing that says which type it is absent *from* is the place it is written.
     * So `x: string? = null` types the literal `string?`, `list.add(null)` types it by the parameter,
     * and a `null` with no context — `println(null)`'s argument is a context, but a bare `null` in an
     * arithmetic expression is not — has no type to be and is refused.
     *
     * Asking for a non-nullable type is refused too, and this is the check that carries the whole
     * feature: `x: string = null` is an error at the assignment rather than a `NullPointerException`
     * at the first use of `x`, which is the entire reason the `?` suffix exists.
     */
    private fun analyzeNullLiteral(expression: NullLiteral, expectedType: NekoType?): NekoType {
        if (expectedType != null && !expectedType.isError && expectedType is NullableType) {
            return expectedType
        }
        diagnostics.error(
            "'null' may only be used where a nullable type is expected, but " +
                if (expectedType == null || expectedType.isError) {
                    "nothing here says what type it should be absent from; give the expression a " +
                        "name of a nullable type, or compare it against a value that has one"
                } else {
                    "${expectedType.displayName} is not one; write ${expectedType.displayName}? to " +
                        "allow the value to be absent, or assign a real value"
                },
            expression.span,
        )
        return ErrorType
    }

    /**
     * The type of a whole-number literal: the one its context asks for, or `int`.
     *
     * This is the *whole* of Neko's implicit conversion, and it is deliberately confined to literals.
     * A literal is the one expression that names a value without belonging to a type yet, so
     * `x: byte = 1` and `x: long = 1` can both mean what they look like. A variable is not: its type
     * is already decided, so `x: long = someInt` stays an error. Java would widen that `int` silently;
     * Neko does not, because a rule that reads "a value keeps its type" is one a reader can apply
     * without holding a table of which conversions are allowed in which direction.
     *
     * A literal that does not fit the type its context asks for is reported rather than narrowed:
     * `x: byte = 300` is a mistake about the value, and saying so where the value is written is more
     * use than saying the types disagree.
     */
    private fun analyzeIntegerLiteral(expression: IntegerLiteral, expectedType: NekoType?): NekoType {
        if (expression.isLong) {
            // `1L` names the 64-bit type outright, so a context cannot narrow it back: `x: int = 1L` is
            // a disagreement about types rather than about a value that happens not to fit. Answering
            // `ErrorType` is what keeps it to one diagnostic — the suffix is the whole story, and
            // "cannot assign a long to an int" would only be the same sentence in other words.
            // `long?` is a context a long literal fits: the suffix decides the *type*, and the
            // wrapper only adds permission for the value to be absent, so the question is about the
            // type underneath it.
            val context = expectedType?.unwrapNullable()
            if (context != null && !context.isError && context != LongType) {
                reportOnce(expression) {
                    "the 'L' suffix makes this literal a long, which cannot be used where " +
                        "${context.displayName} is expected; write ${expression.signedText} " +
                        "without the 'L' to make it a ${context.displayName}"
                }
                return ErrorType
            }
            return LongType
        }
        return expectedType.toLiteralIntType(expression)
    }

    /**
     * How an untyped whole-number literal is read where [expected] is expected.
     *
     * The answer is always the requested type or `int`, and a literal that cannot be read as the
     * requested type answers `int` — which is the type it has on its own — after its own diagnostic.
     * That is deliberate: the mistake is that the *value* does not fit, and saying so once is more use
     * than also claiming that an `int` cannot be stored in a `long`.
     */
    private fun NekoType?.toLiteralIntType(literal: IntegerLiteral): NekoType {
        // What the context asks for is the type *underneath* any `?`: `x: byte? = 1` gives the
        // literal the same byte it would have without the suffix, and only then has its value
        // permission to be absent. Boxing it into the wrapper is code generation's business.
        val target = this?.unwrapNullable()
        if (target == null || target.isError || !target.isNumeric) return IntType

        if (target == FloatType || target == DoubleType) {
            // A whole number in a fractional context names a *different* value: `x: double = 1` would
            // need `1.0`, because nothing here converts 1 to 1.0 on the author's behalf.
            reportOnce(literal) {
                "the whole-number literal ${literal.signedText} has type int, which cannot be used " +
                    "where ${target.displayName} is expected; write ${literal.signedText}.0 to make " +
                    "it a ${target.displayName}, or give the expression a name of the type you want"
            }
            return ErrorType
        }

        if (target.integralRange()?.contains(literal.signedValue) == true) return target
        reportLiteralOutOfRange(literal, target)
        return ErrorType
    }

    /**
     * Says that a written number does not fit the type the surrounding context gives it.
     *
     * The message names the whole range rather than only the maximum, because half of these mistakes
     * are about the *low* end — `x: char = -1`, or a `byte` below -128 — and a reader who is told only
     * the maximum has to work out the rest.
     */
    private fun reportLiteralOutOfRange(literal: IntegerLiteral, target: NekoType) {
        val range = target.integralRange() ?: return
        reportOnce(literal) {
            "integer literal ${literal.signedText} does not fit in '${target.displayName}', whose " +
                "range is ${range.first}..${range.last}; ${widerTypeHint(target)}"
        }
    }

    /**
     * Reports [message] at [literal] the first time it is asked to, and silently after that.
     *
     * Analysis reaches one expression more than once — a field's initializer is analysed when its
     * signature is resolved and again when its class' body is — and a diagnostic about a *literal* is
     * a fact about that literal, not about the visit. Reporting it per visit would print the same
     * sentence twice for one mistake, which reads as two mistakes.
     */
    private inline fun reportOnce(literal: IntegerLiteral, message: () -> String) {
        if (literal.isOutOfRange) return
        literal.isOutOfRange = true
        diagnostics.error(message(), literal.span)
    }

    /** The second half of [reportLiteralOutOfRange]: what to write instead. */
    private fun widerTypeHint(target: NekoType): String = when (target) {
        ByteType -> "write 'short' or a wider type, or a value in range"
        ShortType, CharType -> "write 'int' or a wider type, or a value in range"
        IntType -> "write 'long' for a wider whole number, or a value in range"
        else -> "write a value in range"
    }

    /**
     * The type of a fractional literal: `float` when the source asked for one with `f`/`F`, and
     * `double` otherwise.
     *
     * There is no contextual conversion for these beyond that suffix. `1.5` is a `double` and a
     * `float` is a *narrower* type than it, so `x: float = 1.5` would need a narrowing conversion
     * Java also refuses without a cast — and Neko has no cast. Writing `1.5f` says which one is meant
     * and costs one character.
     */
    private fun analyzeDoubleLiteral(expression: DoubleLiteral, expectedType: NekoType?): NekoType {
        val type = if (expression.isFloat) FloatType else DoubleType
        // A `double?` is still a context where a `double` literal belongs; only the type underneath
        // the `?` can disagree with the literal's own.
        val context = expectedType?.unwrapNullable()
        if (context != null && !context.isError && context != type &&
            !expression.isTypeReported
        ) {
            expression.isTypeReported = true
            val text = expression.span.text
            diagnostics.error(
                "the floating-point literal $text has type ${type.displayName}, which cannot be " +
                    "used where ${context.displayName} is expected; write " +
                    (if (context == FloatType) {
                        "${text}f to make it a float"
                    } else {
                        "the literal without its 'f' suffix to make it a double"
                    }) + ", or convert the value explicitly",
                expression.span,
            )
            // `ErrorType` because the mistake has been named: the *suffix* is what is wrong, and
            // following it with "cannot assign a double to a float" would describe the same one
            // mistake twice, the second time less usefully.
            return ErrorType
        }
        return type
    }

    /**
     * The type of a character literal: `char`, or the integral type its context asks for when the
     * character's code point fits.
     *
     * `x: int = 'a'` works for the same reason `x: byte = 1` does — `'a'` is a number written in
     * quotes — and it is the spelling a program needs in order to compare a character against an
     * arithmetic result.
     */
    private fun analyzeCharLiteral(expression: CharLiteral, expectedType: NekoType?): NekoType {
        val code = expression.value.code.toLong()
        // The `?` is not the question: `x: int? = 'a'` asks for an `int` that may be absent, and the
        // character's code point fits one.
        val context = expectedType?.unwrapNullable()
        val requested = context.takeIf { it != null && !it.isError && it != CharType }
        if (requested != null && requested.isNumeric && requested.integralRange()?.contains(code) == true) {
            return requested
        }
        return CharType
    }

    /**
     * How an untyped whole-number literal is read where [expected] is expected.
     *
     * The answer is always the expected type or `int`, and never a third thing: a literal that does
     * not fit is still an `int` literal, and the fact that its *value* is out of range is recorded on
     * the node so the single caller that reports it can name the type that was asked for. Returning a
     * "narrowed but overflowing" type instead would make the diagnostic claim a `byte` literal exists
     * that cannot be stored in a `byte`.
     */
    private fun NekoType?.toLiteralIntType(magnitude: Long, literal: IntegerLiteral): NekoType {
        // As in the single-argument overload: the type underneath a `?` is what a literal is read as.
        val target = this?.unwrapNullable()
        if (target == null || target.isError || !target.isNumeric) return IntType
        if (target == FloatType || target == DoubleType) {
            // A whole number in a fractional context is a *different* value: `x: double = 1` would
            // need `1.0`, because nothing here converts 1 to 1.0 on the author's behalf.
            diagnostics.error(
                "the whole-number literal ${literal.span.text} has type int, which cannot be used " +
                    "where ${target.displayName} is expected; write ${literal.span.text}.0 to make " +
                    "it a ${target.displayName}",
                literal.span,
            )
            return IntType
        }
        if (target == CharType || target.integralRange()?.contains(magnitude) == true) return target
        literal.isOutOfRange = true
        literal.outOfRangeType = target
        return IntType
    }

    /**
     * `this` — the object the enclosing instance member was invoked on.
     *
     * Only an instance member has one: a `static` function runs with no object, and a lambda body
     * runs on whatever object its interface was invoked on, which is not the enclosing class. Both
     * cases are reported rather than silently resolving to a wrong object.
     */
    private fun analyzeThis(expression: ThisExpression): NekoType {
        if (currentLambda != null) {
            diagnostics.error(
                "'this' cannot be used inside a lambda: a lambda body runs on its own object, " +
                    "which is not the one the enclosing function was called on",
                expression.keywordSpan,
            )
            return ErrorType
        }
        if (currentExtractor != null) {
            // The handler is an object of its own too. Allowed, `this` would read the *handler* and
            // its members, which is not what the author means and would compile to a field access on
            // the wrong class.
            diagnostics.error(
                "'this' cannot be used inside a 'try' handler: the block runs on the handler object, " +
                    "not on the one the enclosing function was called on",
                expression.keywordSpan,
            )
            return ErrorType
        }

        val classSymbol = implicitThisClass ?: run {
            val function = currentFunction
            diagnostics.error(
                if (function == null) "'this' can only be used inside a class member"
                else "'this' cannot be used in '${function.name}' because it is static: a static " +
                    "function runs without an object",
                expression.keywordSpan,
            )
            return ErrorType
        }

        return ClassType(classSymbol.jvmInternalName, classSymbol.name)
    }

    /**
     * `array[index]` as a value: the element type of [IndexExpression.array].
     *
     * Neko has no array literal, so an array can only come from elsewhere — a parameter, a field,
     * or a call into another language. Reading one element is therefore the only way to get at the
     * contents, which is what a `try` handler needs when it is handed an array.
     */
    private fun analyzeIndexRead(expression: IndexExpression): NekoType {
        val arrayType = requireArray(analyzeExpression(expression.array), expression.array.span)
        val element = arrayElementType(arrayType, expression.array.span)
        analyzeIndex(expression.index, expression.indexSpan)
        return element ?: ErrorType
    }

    /**
     * `a > b > c`, which means `a > b && b > c`.
     *
     * Every operator in the chain is relational, so every operand between two operators participates
     * in two comparisons and must have the same numeric type as its neighbour it shares with the next
     * one; only the result is a bool.
     *
     * Each operand after the first is analysed in the type of the one before it, for the reason every
     * other context does it: `b > 2 > 1` on a `byte` is a chain of `byte` comparisons, so both of
     * those literals are `byte`s. A *value* of another type is not converted by that, which is what
     * keeps `i > l > 1` an error.
     */
    private fun analyzeChainedComparison(expression: ChainedComparisonExpression): NekoType {
        val operandTypes = mutableListOf<NekoType>()
        for ((index, operand) in expression.operands.withIndex()) {
            operandTypes += analyzeExpression(operand, operandTypes.getOrNull(index - 1))
        }
        var invalid = false

        for ((index, type) in operandTypes.withIndex()) {
            if (type.isError) {
                invalid = true
                continue
            }
            if (!type.isNumeric) {
                invalid = true
                diagnostics.error(
                    "a chained comparison compares numeric values but operand ${index + 1} here has " +
                        "type ${type.displayName}",
                    expression.operands[index].span,
                )
            }
        }

        // The neighbouring pairs, which the chain is built from: `a > b > c` is two comparisons and
        // the operand in the middle takes part in both. A pair whose second operand was read as the
        // first one's type cannot disagree, so anything reported here is a named type that the chain
        // wanted to convert and will not.
        for (index in expression.operators.indices) {
            val left = operandTypes[index]
            val right = operandTypes[index + 1]
            if (left.isError || right.isError) continue
            if (!left.isNumeric || !right.isNumeric) continue
            if (left != right) {
                invalid = true
                diagnostics.error(
                    "a chained comparison compares values of one type, but operand ${index + 1} has " +
                        "type ${left.displayName} and operand ${index + 2} has type ${right.displayName}",
                    expression.operands[index + 1].span,
                )
            }
        }

        return if (invalid) ErrorType else BoolType
    }

    private fun analyzeUnary(expression: UnaryExpression, expectedType: NekoType?): NekoType {
        val operandExpression = expression.operand
        // `-128` is a single value, not a negation applied to 128, and the difference is visible: 128
        // does not fit in a `byte` and -128 does. A whole-number literal that a `-` precedes is
        // therefore checked against the *negated* value, which is the one the reader wrote.
        val negatedLiteral = operandExpression as? IntegerLiteral
        val literalUnderMinus = if (expression.operator == UnaryOperator.NEGATE) negatedLiteral else null
        val operand = if (literalUnderMinus != null) {
            analyseLiteralWithSign(literalUnderMinus, expectedType)
        } else {
            analyzeExpression(operandExpression, expectedType)
        }

        return when (expression.operator) {
            UnaryOperator.NEGATE -> {
                if (!operand.isError && !operand.isNumeric) {
                    diagnostics.error(
                        "unary '-' requires a numeric operand but this expression has type " +
                            operand.displayName,
                        operandExpression.span,
                    )
                    return ErrorType
                }
                // The result keeps the operand's own type: `-1L` is a long and `-1.5f` a float, so a
                // negated value can be used wherever an unnegated one could.
                operand
            }

            UnaryOperator.NOT -> {
                if (!operand.isError && operand != BoolType) {
                    diagnostics.error(
                        "unary '!' requires a bool operand but this expression has type " +
                            operand.displayName,
                        operandExpression.span,
                    )
                }
                // What `!` proves is read from the *condition* by [factsTrue] and [factsFalse],
                // which undo the negation where it stands. Nothing is applied here: `!x` is a value
                // like any other, and a fact recorded while typing one would outlive the test it
                // came from.
                BoolType
            }
        }
    }

    /**
     * Types the literal under a `-` as the negated value the source actually names.
     *
     * A `1L` under the minus keeps its suffix, and the literal's resolved type is what the emitter
     * reads, so recording the sign on the node is also what makes `-1L` compile to `-1L` rather than to
     * a negation the language never wrote.
     */
    private fun analyseLiteralWithSign(literal: IntegerLiteral, expectedType: NekoType?): NekoType {
        literal.isNegated = true
        return analyzeIntegerLiteral(literal, expectedType)
    }

    /**
     * The type of `left operator right`.
     *
     * ### Neko has no numeric promotion
     *
     * Java's rule is that `int + long` is a `long`: the narrower operand is widened, silently, before
     * the operation. Neko does not do that, and this is where the decision lives. Two operands must
     * have the *same* numeric type, and the result is that type — `long + long` is a `long`,
     * `float * float` is a `float`, and `int + long` is an error, exactly as `int + string` already
     * is. That is a deliberate departure from Java, not an omission: this language's rule everywhere
     * else is that a named type is what it says, and a table of widenings is a second rule to learn
     * on top of it. A program that wants one type says so with a literal — `x: long = 1` — which is
     * where the conversion is visible in the source.
     *
     * [expectedType] is passed down to the operands, which is the one thing that makes
     * `x: long = 1 + 2` work: both literals are read as `long`, so the operation is `long + long`.
     */
    private fun analyzeBinary(expression: BinaryExpression, expectedType: NekoType?): NekoType {
        val operator = expression.operator

        // `&&` and `||` short-circuit, so their operands are booleans rather than numbers and the
        // context of the whole expression says nothing about either of them.
        if (operator == BinaryOperator.AND || operator == BinaryOperator.OR) {
            val left = analyzeExpression(expression.left)
            // Short-circuiting is what makes a fact available on the right, and *which* fact is what
            // the operator decides: `&&` reaches its second operand only when the first held, while
            // `||` reaches it only when the first did not. So `x == null || x.f` knows `x` is there,
            // for the same reason `x != null && x.f` does.
            val incoming = saveNarrowings()
            applyFacts(if (operator == BinaryOperator.AND) factsTrue(expression.left) else factsFalse(expression.left))
            val right = analyzeExpression(expression.right)
            restoreNarrowings(incoming)
            if (left.isError || right.isError) return ErrorType
            requireBoolOperand(left, expression.left, operator)
            requireBoolOperand(right, expression.right, operator)
            return BoolType
        }

        // `x == null` reads the `null` in the type of the *other* operand, whichever side it is
        // written on: the literal is the only expression that cannot say what type it is absent from,
        // so `null == x` has to be as legal as `x == null` and mean the same thing.
        if (operator == BinaryOperator.EQUAL || operator == BinaryOperator.NOT_EQUAL) {
            val literal = when {
                expression.left is NullLiteral -> expression.left to expression.right
                expression.right is NullLiteral -> expression.right to expression.left
                else -> null
            }
            if (literal != null) {
                val (nullSide, other) = literal
                val otherType = analyzeExpression(other, expectedType)
                if (otherType.isError) return ErrorType
                // The question is only worth asking of a value that can be absent. A non-nullable one
                // has an answer known at compile time, so it is reported here with the reason rather
                // than with the general "where a nullable type is expected" advice, which would tell
                // the reader to fix the wrong expression. The *declared* type is what decides, not the
                // type this read happens to have: a value already narrowed to `Cat` is still a `Cat?`
                // that a second test may legitimately ask about.
                val declared = declaredTypeOf(other)
                if (declared !is NullableType) {
                    diagnostics.error(
                        "${other.span.text} has type ${declared.displayName}, which is not " +
                            "nullable, so it can never be null; write ${declared.displayName}? for " +
                            "the value to be able to be absent",
                        expression.operatorSpan,
                    )
                    return ErrorType
                }
                analyzeExpression(nullSide, declared)
                return BoolType
            }
        }

        // The one exception is concatenation. When either the left operand or the declaration already
        // says `string`, both operands are read on their **own** terms, because in `"n = " + 1L` the
        // `1L` is a `long` and saying so would be true but useless: the `L` is correct, and nothing
        // asks it to be anything else. Passing the `string` down instead made the literal report
        // "the 'L' suffix makes this literal a long, which cannot be used where string is expected;
        // write 1 without the 'L'" — advice that would narrow a `long` to an `int` and change what
        // the program prints.
        //
        // `s: string = 1 + 2` is still refused, just one step later: neither operand is a `string`,
        // so the `+` is arithmetic and answers an `int`, which the declaration then rejects — the
        // real mistake, rather than a complaint about either operand.
        val concatenating = operator == BinaryOperator.ADD &&
            (expectedType == StringType || expression.left.isStringLiteral())
        val left = analyzeExpression(expression.left, if (concatenating) null else expectedType)
        val rightExpected = if (concatenating || left == StringType) null else left
        val right = analyzeExpression(expression.right, rightExpected)

        if (left.isError || right.isError) return ErrorType

        return when (operator) {
            BinaryOperator.SUBTRACT, BinaryOperator.MULTIPLY,
            BinaryOperator.DIVIDE, BinaryOperator.REMAINDER,
            -> requireSameNumericType(left, right, expression, operator)

            // `string + x` is concatenation, and `x` may be of **any** type: that is what makes
            // string building usable at all — `"n = " + count` is the whole point. The JVM agrees,
            // and has an `append` overload per type, so nothing is guessed or converted here.
            //
            // Checked before the numeric rule, because `"a" + 1` must not be reported as "requires
            // int operands": the operand that is `string` is not the mistake, and telling the reader
            // to write `1` as a number would be advice pointing the wrong way.
            BinaryOperator.ADD -> when {
                left == StringType || right == StringType -> checkConcatenation(left, right, expression)
                else -> requireSameNumericType(left, right, expression, operator)
            }

            BinaryOperator.EQUAL, BinaryOperator.NOT_EQUAL -> compareTypes(left, right, expression)

            BinaryOperator.LESS, BinaryOperator.LESS_EQUAL,
            BinaryOperator.GREATER, BinaryOperator.GREATER_EQUAL,
            -> requireSameNumericType(left, right, expression, operator)

            BinaryOperator.AND, BinaryOperator.OR -> error("handled above")
        }
    }

    /**
     * Checks that `==` or `!=` can be asked of [left] and [right], and answers the `bool` it is worth.
     *
     * The test is assignability in *either* direction, which is what makes `a == b` work when one side
     * is the other's supertype: a `Cat` and an `Animal` can be compared because a `Cat` is an
     * `Animal`, and two unrelated classes cannot because nothing says what comparing them would mean.
     * A nullable type accepts its own inner type in one of those directions, so `x == 1` on an `int?`
     * passes for the same reason.
     *
     * [isAssignable] rather than the two types' own rule, so that a *declared* subtype counts: an
     * interface and the class implementing it are comparable, and only the class symbols know that.
     * Either direction alone would be wrong — `Cat? == Cat()` has to be a legal question.
     */
    private fun compareTypes(left: NekoType, right: NekoType, expression: BinaryExpression): NekoType {
        if (!isAssignable(left, right) && !isAssignable(right, left)) {
            diagnostics.error(
                "cannot compare a value of type ${left.displayName} with one of type " +
                    right.displayName,
                expression.operatorSpan,
            )
            return ErrorType
        }
        return BoolType
    }

    /**
     * Checks an arithmetic or relational expression's two operands and answers their shared type.
     *
     * Both operands have to be numeric and to be the *same* numeric type; the result is that type —
     * except for a relational operator, whose result is the `bool` the answer actually is. See
     * [analyzeBinary] for why mixing is refused rather than promoted.
     *
     * The mismatched operand is the one reported, so `1 + 2L` points at `2L` and not at the `+`. Both
     * messages keep the phrase "int operands" that the language used when `int` was the only numeric
     * type, because a reader who has learned that `int + string` is rejected should recognise
     * `bool > bool` being rejected beside it.
     */
    private fun requireSameNumericType(
        left: NekoType,
        right: NekoType,
        expression: BinaryExpression,
        operator: BinaryOperator,
    ): NekoType {
        if (!left.isNumeric) {
            diagnostics.error(
                "operator '${operator.display}' requires int operands but this operand has type " +
                    left.displayName,
                expression.left.span,
            )
            return ErrorType
        }
        if (!right.isNumeric) {
            diagnostics.error(
                "operator '${operator.display}' requires int operands but this operand has type " +
                    right.displayName,
                expression.right.span,
            )
            return ErrorType
        }
        if (left != right) {
            diagnostics.error(
                "operator '${operator.display}' requires operands of the same numeric type, but " +
                    "this one has type ${right.displayName} where ${left.displayName} is expected; " +
                    "Neko does not convert between numeric types, so write a ${left.displayName} " +
                    "value or use a ${right.displayName} on both sides",
                expression.right.span,
            )
            return ErrorType
        }
        return if (operator.isRelational) BoolType else left
    }

    /**
     * Whether this operand is a `string` literal, so the `+` it takes part in can only be concatenation.
     *
     * It is worth asking about the *syntax* rather than the type because the left operand is analysed
     * before the decision is made: in `s: string = 1 + "a"` the `string` context the declaration
     * supplies must not be handed to the `1`, or the literal would be read as a `string` and the
     * concatenation never recognised. A literal says outright what it is; nothing has to be inferred.
     *
     * Grouping needs no case of its own: the parser drops the parentheses and keeps the expression.
     */
    private fun Expression.isStringLiteral(): Boolean = this is StringLiteral

    /**
     * Checks one side of a `+` that is concatenation because the other side is a `string`.
     *
     * The rule is deliberately wide: **any** type may stand beside a `string`, because the class the
     * emitter builds this with — `StringBuilder` — has an `append` overload for every one of them.
     * `"n = " + count`, `"ok? " + flag`, `"at " + point` all work, and a value of a class type joins
     * through `append(Object)`, which is `String.valueOf`, so a class's own `toString` decides what
     * it prints.
     *
     * Three things are *not* allowed, and each is refused for a different reason:
     *
     *  * **`void`**, because a call that answers nothing has no value to concatenate. Accepting it
     *    would emit an `append` with nothing on the stack under it.
     *  * **`null` on both sides** (`null + null`), because neither operand says what it is absent
     *    from, so there is no `string` in the expression to concatenate onto. The literal is analysed
     *    without a context and reports its own, better diagnostic.
     *
     * A single `null` beside a `string` *is* allowed, and prints `null` — that is what
     * `StringBuilder.append(Object)` does, and what a reader expects of a missing value inside a
     * message. It needs no special case here: the nullable check already ran when the literal was
     * analysed in the `string` context the other operand supplied.
     */
    private fun checkConcatenation(
        left: NekoType,
        right: NekoType,
        expression: BinaryExpression,
    ): NekoType {
        if (left === VoidType) {
            diagnostics.error(
                "operator '+' cannot concatenate ${expression.left.span.text}, which produces no " +
                    "value; a function with no declared return type has nothing to append",
                expression.left.span,
            )
            return ErrorType
        }
        if (right === VoidType) {
            diagnostics.error(
                "operator '+' cannot concatenate ${expression.right.span.text}, which produces no " +
                    "value; a function with no declared return type has nothing to append",
                expression.right.span,
            )
            return ErrorType
        }
        return StringType
    }

    private fun requireBoolOperand(type: NekoType, operand: Expression, operator: BinaryOperator) {
        if (type.isError || type == BoolType) return
        diagnostics.error(
            "operator '${operator.display}' requires bool operands but this operand has type " +
                type.displayName,
            operand.span,
        )
    }

    /**
     * Resolves `callee(arguments)`.
     *
     * Three shapes are supported:
     *
     *  1. a *function* called by name, resolved against built-ins and then user declarations;
     *  2. a *value* of function type, which is how `lambda()(1, 2)` works: `lambda()` produces a
     *     `[int, int] -> int` and the outer call invokes it;
     *  3. a local or field holding a function value, e.g. `f(1)` where `f` was assigned a lambda.
     */
    private fun analyzeCall(expression: CallExpression, expectedType: NekoType?): NekoType {
        val callee = expression.callee

        if (callee is NameExpression) {
            val candidates = functionCandidates(callee.name, expression.arguments.size)
            if (candidates.isNotEmpty()) {
                val selected = selectOverload(candidates, expression)
                expression.callee.resolution = selected
                // A call is also a reference, so a bare call to an instance function from a static
                // context is just as unreachable as a bare read of an instance field: there is no
                // receiver to invoke it on. Reporting it here keeps the two spellings consistent.
                checkMemberReachable(selected, callee.span)
                checkCallableNormally(selected, callee.span)
                checkTypeArguments(expression, selected.typeParameters, callee.name)
                // [selectOverload] has already analysed every argument, so their types are known
                // here and the written arguments can be held against them.
                checkTypeArgumentFits(expression, selected.typeParameters, callee.name, selected)
                // A declared function is resolved by name, so there is no value that could be absent.
                if (expression.isSafeCall) {
                    reportRedundantSafeCall(
                        callee.span,
                        "'${callee.name}' is a declared function, so it is never null",
                        "${callee.name}(…)",
                    )
                }
                return selected.returnType
            }

            if (functionNamed(callee.name).isNotEmpty()) {
                // The name exists, but not with this number of arguments: report it here, at the
                // callee, rather than letting it decay into "unresolved reference".
                val arities = functionNamed(callee.name)
                    .map { it.parameterTypes.size }
                    .distinct()
                    .sorted()
                    .joinToString(" or ")
                val count = expression.arguments.size
                diagnostics.error(
                    "function '${callee.name}' expects $arities argument(s) but $count " +
                        "${if (count == 1) "was" else "were"} provided",
                    callee.span,
                )
                for (argument in expression.arguments) analyzeExpression(argument)
                return ErrorType
            }

            // A capitalized name that is not a function constructs a class: `CustomException()`.
            if (callee.name.firstOrNull()?.isUpperCase() == true) {
                // A class name is not a value either: `Cls?()` would be a construction that always
                // happens, spelled as if it might not.
                if (expression.isSafeCall) {
                    reportRedundantSafeCall(
                        callee.span,
                        "$callee.name is a class name, so constructing it is never null",
                        "${callee.name}(…)",
                    )
                }
                return analyzeConstruction(expression, callee)
            }

            // Not a function: it may be a variable or field holding a function value. When the name
            // resolves to nothing at all, say so in call terms — "unresolved reference" would leave
            // the reader unsure whether the call or the name was the problem.
            val value = lookup(callee.name)
            if (value == null) {
                diagnostics.error(
                "there is no function named '${callee.name}'",
                callee.span,
                unresolved = true,
            )
                for (argument in expression.arguments) analyzeExpression(argument)
                return ErrorType
            }
            callee.resolution = value
            // The callee is a *value* of function type, so code generation has to know its type to
            // pick the interface to `invokeinterface` on. Analysing it as an expression would record
            // this too, but the name was already resolved by hand above. A narrowed read is what makes
            // `if (f != null) f(1)` call without the question mark: the test proved `f` is there, so
            // the call is as safe as one on a plain function.
            val valueType = readTypeOf(value)
            callee.resolvedType = valueType
            checkMemberReachable(value, callee.span)
            return callFunctionValue(
                expression = expression,
                calleeType = valueType,
                calleeName = value.name,
                safeCall = expression.isSafeCall,
            )
        }

        if (callee is MemberAccessExpression) {
            return analyzeMemberCall(expression, callee)
        }

        // Calling the result of another call, e.g. `lambda()(1, 2)`.
        val calleeType = analyzeExpression(callee, expectedType)
        if (calleeType.isError) {
            for (argument in expression.arguments) analyzeExpression(argument)
            return ErrorType
        }
        return callFunctionValue(
            expression = expression,
            calleeType = calleeType,
            calleeName = callee.span.text,
            safeCall = expression.isSafeCall,
        )
    }

    /**
     * `obj.f(...)` — invokes a member function on an object.
     *
     * This is how a non-static function is called: the object has to exist first, and its type picks
     * the overload. A static function reached this way works too, but the analyzer warns the reader
     * off it, because the class name reads better and does not require an instance.
     */
    private fun analyzeMemberCall(expression: CallExpression, callee: MemberAccessExpression): NekoType {
        // `Sample.f(1)` calls a static function by class name, which needs no object.
        val className = (callee.receiver as? NameExpression)?.name
        val namedClass = className?.let { classNamed(it, callee.memberSpan) }
        if (namedClass != null) {
            if (callee.isSafeCall) reportSafeCallOnClassName(callee.memberSpan, className)
            callee.receiverIsClass = true
            callee.receiver.resolvedType = ClassType(namedClass.jvmInternalName, namedClass.name)
            return resolveMemberCall(expression, callee, namedClass, className, viaClass = true)
        }

        // A *qualified* receiver — `java.lang.System.currentTimeMillis()`, or a Neko class named by
        // its package. The receiver parses as nested member accesses rather than a name, so it has to
        // be tried as a qualified class name before anything else happens to it; otherwise
        // `java.lang` would be looked up as a value and reported as an unresolved name.
        //
        // The whole dotted text — receiver plus member — is tried first, because `java.util.HashMap()`
        // and `java.lang.System.exit(0)` are *both* calls whose callee chain spells a qualified name
        // and only the last segment decides which reading applies. Whichever segment is a class, the
        // call is that class: `…HashMap()` constructs it, `…System.exit` calls its static member.
        //
        // Everything here goes through the non-reporting [qualifiedClassOrNull]: a chain like
        // `list.get(0)` also reaches this point, and it must fall through to the ordinary value path
        // without a word about "unknown type".
        val dotted = qualifiedClassNameOf(callee.receiver)
        if (dotted != null && '.' in dotted) {
            val whole = "$dotted.${callee.memberName}"
            val wholeClass = qualifiedClassOrNull(whole)
            if (wholeClass != null) {
                // `java.util.HashMap()` — the last segment is the class, so this is a construction.
                if (callee.isSafeCall) reportSafeCallOnClassName(callee.memberSpan, whole)
                expression.constructedType = wholeClass
                for (argument in expression.arguments) analyzeExpression(argument)
                return wholeClass
            }

            val receiverClass = qualifiedClassOrNull(dotted)
            if (receiverClass != null) {
                // The receiver is not a value, so it is left un-analysed; its type records what it
                // named, which is what the emitter needs to pick the owner for `invokestatic`.
                if (callee.isSafeCall) reportSafeCallOnClassName(callee.memberSpan, dotted)
                callee.receiverIsClass = true
                callee.receiver.resolvedType = receiverClass
                val symbol = classes[receiverClass.internalName]
                return if (symbol != null) {
                    resolveMemberCall(expression, callee, symbol, receiverClass.simpleName, viaClass = true)
                } else {
                    platformQualifiedCall(expression, callee, receiverClass)
                }
            }
        }

        // A JDK class named as the receiver — `System.currentTimeMillis()`, `Math.abs(x)`, or a class
        // from an imported package like `Arrays.toString()`. The program does not declare it, so
        // there is no symbol to check members against; the reflected class answers instead. Without
        // this branch these calls were refused as an unresolved *name*, which is the one thing they are
        // not: the reader named a class.
        val platformType = platformClassType(className)
        if (platformType != null) {
            if (callee.isSafeCall) reportSafeCallOnClassName(callee.memberSpan, className!!)
            callee.receiverIsClass = true
            callee.receiver.resolvedType = platformType
            return platformStaticCall(expression, callee, platformType)
        }

        // A qualified chain that *reads* as a class name, but resolved to nothing, has already been
        // described by `resolveClassType`; falling through would analyse `java` as a value and add a
        // second, misleading "unresolved reference 'java'". Two shapes have to be caught here:
        //
        //  * the member is the class — `java.util.Nope()` — so the whole dotted text is the name;
        //  * the receiver is the class — `java.util.Nope.f()` — so the chain is the name.
        //
        // Whether it really was a class name is decided by the capital letter, which is the same rule
        // `resolveClassType` applies; a lowercase last segment is not a class and is left to the value
        // path, where it may well be a legitimate member read.
        // Order matters. When the receiver already ends in a capitalized segment — `java.lang.Nope.f()`
        // — the receiver is the class the reader meant to call into, so it is the name reported; `f`
        // is the member, not a class. Only when the receiver does not read as a class at all —
        // `java.util.Nope()` — is the whole dotted text the candidate, because there the member *is*
        // the class. Choosing the wrong one would report a name the reader never wrote.
        val failedType = dotted?.let { receiver ->
            if ('.' in receiver && receiver.substringAfterLast('.').firstOrNull()?.isUpperCase() == true) {
                receiver
            } else {
                "$receiver.${callee.memberName}"
            }
        }
        if (failedType != null && '.' in failedType && readsAsQualifiedType(failedType)) {
            resolveClassType(failedType, callee.receiver.span)
            for (argument in expression.arguments) analyzeExpression(argument)
            return ErrorType
        }

        val receiverType = analyzeExpression(callee.receiver)

        if (receiverType.isError) {
            for (argument in expression.arguments) analyzeExpression(argument)
            return ErrorType
        }

        // As for a field read: `?.` is the licence to call into a nullable receiver, and the call's
        // own value becomes nullable because it may not have happened at all.
        val safe = callee.isSafeCall
        val present = receiverType.unwrapNullable()
        if (present !is ClassType) {
            for (argument in expression.arguments) analyzeExpression(argument)
            diagnostics.error(
                "cannot call member '${callee.memberName}' on a value of type " +
                    "${receiverType.displayName}, which has no members",
                callee.memberSpan,
            )
            return ErrorType
        }
        if (receiverType is NullableType && !safe) {
            for (argument in expression.arguments) analyzeExpression(argument)
            val arguments = expression.arguments.joinToString(", ") { it.span.text }
            reportUnsafeDereference(
                "cannot call member '${callee.memberName}' on a value of type " +
                    receiverType.displayName,
                safeCallAdvice(callee.receiver.span.text, callee.memberName, "($arguments)"),
                callee.memberSpan,
            )
            return ErrorType
        }

        val classSymbol = classOf(present)
        if (classSymbol == null) {
            // A JDK type: the program never compiled it, so there is no declaration to check against.
            // Reflection answers instead — but only once the receiver has been found to be a value of
            // a class that really exists. `platformInstanceCall` reports the rest.
            val returned = platformInstanceCall(expression, callee, present)
            return if (safe) nullableResultOf(returned) else returned
        }
        val returned =
            resolveMemberCall(expression, callee, classSymbol, present.displayName, viaClass = false)
        return if (safe) nullableResultOf(returned) else returned
    }

    /**
     * Picks the overload of `Recv.name(...)` and records how the code generator must call it.
     *
     * [viaClass] distinguishes `Cls.f(1)` from `obj.f(1)`: the former can only reach a `static`
     * function, while the latter reaches an instance one and must evaluate its receiver.
     */
    private fun resolveMemberCall(
        expression: CallExpression,
        callee: MemberAccessExpression,
        classSymbol: ClassSymbol,
        receiverName: String,
        viaClass: Boolean,
    ): NekoType {
        val overloads = classSymbol.allFunctions()[callee.memberName].orEmpty()
        if (overloads.isEmpty()) {
            for (argument in expression.arguments) analyzeExpression(argument)
            if (classSymbol.findField(callee.memberName) != null) {
                diagnostics.error(
                    "'${callee.memberName}' is a field of $receiverName, not a function, so it " +
                        "cannot be called",
                    callee.memberSpan,
                )
            } else {
                diagnostics.error(
                    "$receiverName has no member named '${callee.memberName}'",
                    callee.memberSpan,
                    unresolved = true,
                )
            }
            return ErrorType
        }

        val arity = expression.arguments.size
        val candidate = overloads.firstOrNull { it.parameterTypes.size == arity }
        if (candidate == null) {
            for (argument in expression.arguments) analyzeExpression(argument)
            val arities = overloads.map { it.parameterTypes.size }.distinct().sorted().joinToString(" or ")
            diagnostics.error(
                "function '${callee.memberName}' expects $arities argument(s) but $arity " +
                    "${if (arity == 1) "was" else "were"} provided",
                expression.argumentListSpan,
            )
            return ErrorType
        }

        if (viaClass && !candidate.isStatic) {
            diagnostics.error(
                "function '${candidate.name}' is an instance member of $receiverName, so it cannot " +
                    "be called from the class; create an object first and write 'obj.${candidate.name}(...)'",
                callee.memberSpan,
            )
        }
        if (!viaClass && candidate.isStatic) {
            diagnostics.error(
                "function '${candidate.name}' is a static member of $receiverName; write " +
                    "'$receiverName.${candidate.name}(...)' to call it without an object",
                callee.memberSpan,
            )
        }

        callee.resolvedFunction = candidate
        callee.resolvedType = candidate.type
        checkTypeArguments(expression, candidate.typeParameters, callee.memberName)
        // A static call needs no receiver; an instance one does, and it must be evaluated once.
        if (!candidate.isStatic) expression.instanceReceiver = callee.receiver
        expression.instanceMethod = candidate

        // `obj.f?()` — a member function is resolved by name, so unlike a function *value* there is
        // nothing that could be absent. The `?` after the member would quietly mean the same as no
        // `?` at all, so it is reported rather than ignored. The meaningful safe form is
        // `obj?.f()`, which is `callee.isSafeCall` and is left alone.
        if (expression.isSafeCall) {
            val arguments = expression.arguments.joinToString(", ") { it.span.text }
            val dot = if (callee.isSafeCall) "?." else "."
            reportRedundantSafeCall(
                callee.memberSpan,
                "'${callee.memberName}' is a function of $receiverName, so it is never null",
                "${callee.receiver.span.text}$dot${callee.memberName}($arguments)",
            )
        }

        // The receiver *is* the object, so an instance member reached through one is always
        // reachable — there is nothing for `checkMemberReachable` to report. It is only the
        // class-name form, and a bare name, that can find an instance member with no object in hand.
        if (viaClass) checkMemberReachable(candidate, callee.memberSpan)
        checkCallableNormally(candidate, callee.memberSpan)
        checkArguments(expression, candidate.parameterTypes, candidate.name)
        // Only now are the argument types known: `checkTypeArguments`, above, sees the `<...>` but
        // runs before `checkArguments` has typed the values.
        checkTypeArgumentFits(expression, candidate.typeParameters, candidate.name, candidate)
        return candidate.returnType
    }

    /**
     * Resolves `CustomException()`, the language's only way to create an object.
     *
     * Neko has no `new`, so a call on a capitalized name builds an instance. When the class is
     * declared in this program its constructors are known, so the arguments are checked against
     * them; a class from outside the program might accept anything, so its arguments are analysed
     * and left to the JVM verifier to match up.
     */
    private fun analyzeConstruction(expression: CallExpression, callee: NameExpression): NekoType {
        val bare = resolveClassType(callee.name, callee.span)
        if (bare !is ClassType) {
            for (argument in expression.arguments) analyzeExpression(argument)
            return ErrorType
        }

        // The type arguments written on the construction are part of the *type it produces*, not
        // just a decoration on the call: `ArrayList<int>()` builds an `ArrayList<int>`, and a
        // variable declared with that type has to accept it. Resolving them here — rather than only
        // checking their number later — is what keeps the two sides the same type.
        val type = if (expression.typeArguments.isEmpty()) {
            bare
        } else {
            expression.resolvedTypeArguments = expression.typeArguments.map { resolveType(it) }
            parameterisedType(bare, expression.resolvedTypeArguments)
        }

        val classSymbol = classBySimpleName(callee.name)
        if (classSymbol == null) {
            analyzePlatformConstructionArguments(expression, type)
            expression.constructedType = type
            return type
        }
        if (classSymbol.isInterface) {
            for (argument in expression.arguments) analyzeExpression(argument)
            diagnostics.error(
                "'${callee.name}' is an interface, so it cannot be instantiated; write a class " +
                    "that implements it and create that instead",
                callee.span,
            )
            return ErrorType
        }

        val arity = expression.arguments.size
        // A class with no constructor written still has one, so `Cls()` works for it; otherwise the
        // arguments must match a declared constructor exactly.
        val declaresConstructors = classSymbol.constructors.isNotEmpty()
        val constructor = classSymbol.constructors[arity]

        if (declaresConstructors && constructor == null) {
            for (argument in expression.arguments) analyzeExpression(argument)
            val arities = classSymbol.constructors.keys.sorted().joinToString(" or ")
            diagnostics.error(
                "constructor of '${callee.name}' expects $arities argument(s) but $arity " +
                    "${if (arity == 1) "was" else "were"} provided",
                expression.argumentListSpan,
            )
            return ErrorType
        }

        if (!declaresConstructors && arity != 0) {
            for (argument in expression.arguments) analyzeExpression(argument)
            diagnostics.error(
                "'${callee.name}' declares no constructor taking $arity " +
                    "${if (arity == 1) "argument" else "arguments"}, and it has no fields to " +
                    "initialize; write a 'constructor(...)' that accepts them",
                expression.argumentListSpan,
            )
            return ErrorType
        }

        checkArguments(expression, constructor?.parameterTypes.orEmpty(), "${callee.name}(...)")
        expression.resolvedConstructor = constructor
        expression.constructedType = type
        checkConstructedTypeArguments(expression, classSymbol, callee.name)
        return type
    }

    /**
     * Analyses the arguments of a construction whose class the program does not declare.
     *
     * Such a class can only be a platform one — `Thread`, `HashMap` — and its constructors are not in
     * the symbol table, so they cannot be *checked*. What they can still do is supply an expected
     * type, and that turns out to matter for one thing: a lambda written straight into the argument
     * list has no other way to learn what it is. `Thread({ ... })` means `Runnable` and nothing else,
     * but only the reflected constructor knows that, so without this the lambda is told it has no
     * target and cannot infer its own shape.
     *
     * Overloads of the same arity are the whole difficulty — `Thread` has both `Thread(Runnable)` and
     * `Thread(String)` — and there is exactly one fact available to break the tie: a lambda can only
     * fit a functional-interface parameter. So each lambda argument narrows the candidates to those
     * whose parameter in that position is a functional interface. `Thread({ ... })` collapses to
     * `Thread(Runnable)` on that test alone, with no guessing about what the author meant.
     *
     * When the narrowing leaves anything other than one candidate the arguments are analysed plainly,
     * which is what every platform call did before. Nothing here reports a mismatch: whether the
     * arguments fit is left to the JVM verifier, the same division of labour platform calls already
     * had, since the compiler has no symbol to check against.
     */
    private fun analyzePlatformConstructionArguments(expression: CallExpression, type: ClassType) {
        val loaded = loadQualifiedClass(type.internalName.replace('/', '.'))
        val byArity = loaded?.constructors
            ?.filter { it.parameterCount == expression.arguments.size }
            .orEmpty()

        // Positions holding a lambda: the only arguments whose expected type is both unknown and
        // recoverable from the signature.
        val lambdaPositions = expression.arguments
            .withIndex()
            .filter { it.value is LambdaExpression }
            .map { it.index }

        val candidates = if (lambdaPositions.isEmpty()) {
            byArity
        } else {
            byArity.filter { constructor ->
                lambdaPositions.all { position ->
                    val parameter = constructor.parameterTypes.getOrNull(position)
                    parameter != null && singleAbstractMethodOfPlatform(parameter) != null
                }
            }
        }

        if (candidates.size != 1) {
            for (argument in expression.arguments) analyzeExpression(argument)
            return
        }

        val parameters = candidates.single().parameterTypes.map { typeOfReflected(it) }
        expression.arguments.forEachIndexed { index, argument ->
            analyzeExpression(argument, parameters.getOrNull(index))
        }
    }

    /**
     * Calls a *value* of function type, e.g. `f(1)` where `f` holds a lambda.
     *
     * [safeCall] is the `?` of `f?(1)`, and it is the licence to call a value that may be absent —
     * the same trade `?.` makes for a member. Without it a nullable function value cannot be called at
     * all, because there is no member to guard: the callee itself is what might not be there. The
     * answer is nullable whenever the `?` is written, for the same reason `?.` widens its result: a
     * call that was skipped has no result, so the type has to admit the absence. That is deliberately
     * not made conditional on the callee actually being nullable — the rule is "`?` makes the result
     * nullable", which is one sentence, rather than one that also depends on the callee's type.
     */
    private fun callFunctionValue(
        expression: CallExpression,
        calleeType: NekoType,
        calleeName: String,
        safeCall: Boolean = false,
    ): NekoType {
        // `?` guards the absent case, so the type it is asked of is the one underneath.
        val present = calleeType.unwrapNullable()

        if (present !is FunctionType) {
            diagnostics.error(
                "'$calleeName' cannot be called because it is not a function; its type is " +
                    calleeType.displayName,
                expression.callee.span,
            )
            for (argument in expression.arguments) analyzeExpression(argument)
            return ErrorType
        }

        if (calleeType is NullableType && !safeCall) {
            for (argument in expression.arguments) analyzeExpression(argument)
            val arguments = expression.arguments.joinToString(", ") { it.span.text }
            reportUnsafeDereference(
                "cannot call '$calleeName' because its type is ${calleeType.displayName}",
                "write '$calleeName?($arguments)' to do nothing when it is absent",
                expression.callee.span,
            )
            return ErrorType
        }

        checkArguments(expression, present.parameters, calleeName)
        return if (safeCall) nullableResultOf(present.returnType) else present.returnType
    }

    /**
     * Picks the overload that accepts the actual argument types.
     *
     * With a single candidate the arguments are analysed *against that candidate's parameter types*,
     * which is what lets a lambda argument infer its parameter types. With several candidates the
     * arguments are analysed once without a context, and the candidate every argument fits is
     * selected — that is how `println` chooses between its `int`, `string` and `bool` overloads.
     */
    private fun selectOverload(candidates: List<FunctionSymbol>, expression: CallExpression): FunctionSymbol {
        if (candidates.size == 1) {
            val only = candidates.single()
            checkArguments(expression, only.parameterTypes, only.name)
            return only
        }

        val argumentTypes = expression.arguments.map { analyzeExpression(it) }
        val matching = candidates.filter { candidate ->
            argumentTypes.withIndex().all { (index, type) ->
                isAssignable(type, candidate.parameterTypes[index])
            }
        }

        if (matching.size == 1) return matching.single()

        if (matching.isEmpty()) {
            val rendered = argumentTypes.joinToString(", ") { it.displayName }
            diagnostics.error(
                "no overload of '${candidates.first().name}' accepts argument types ($rendered)",
                expression.argumentListSpan,
            )
            return candidates.first()
        }

        // Ambiguous, or every argument is an error type: take the first and move on.
        return matching.first()
    }

    /**
     * Built-in and user-declared functions named [name] that accept [arity] arguments.
     *
     * Members of the class the call is written in win, and that is not an optimisation: a bare call
     * names the nearest declaration, and every class in a file may declare a function of the same
     * name. Searching the whole file first would let a `a()` written in one class resolve to another
     * class's `a` merely because that class was declared earlier, which is how a plain call came to be
     * diagnosed as "declares an 'emit' clause" when the caller's own `a` declared none.
     *
     * The file-wide search stays as the fallback, because a bare call to another class's function is
     * legal here. It is a fallback rather than an alternative, so a member only loses when the
     * enclosing class has no declaration of that name taking this many arguments.
     */
    private fun functionCandidates(name: String, arity: Int): List<FunctionSymbol> {
        val builtin = builtins[name].orEmpty().filter { it.parameterTypes.size == arity }
        if (builtin.isNotEmpty()) return builtin
        val member = currentClass?.allFunctions()?.get(name).orEmpty()
            .filter { it.parameterTypes.size == arity }
        if (member.isNotEmpty()) return member
        return functionNamed(name).filter { it.parameterTypes.size == arity }
    }

    /** Every user-declared function named [name], across all classes in the file. */
    private fun functionNamed(name: String): List<FunctionSymbol> =
        classes.values.flatMap { it.functions[name].orEmpty() }

    private fun checkArguments(expression: CallExpression, parameters: List<NekoType>, name: String) {
        val arguments = expression.arguments
        if (arguments.size != parameters.size) {
            val expected = if (parameters.isEmpty()) {
                "no arguments"
            } else {
                "${parameters.size} argument${if (parameters.size == 1) "" else "s"}"
            }
            diagnostics.error(
                "'$name' expects $expected but ${arguments.size} " +
                    "${if (arguments.size == 1) "was" else "were"} provided",
                expression.argumentListSpan,
            )
        }

        arguments.forEachIndexed { index, argument ->
            val expected = parameters.getOrNull(index)
            val actual = analyzeExpression(argument, expected)
            if (expected != null && !isAssignable(actual, expected)) {
                diagnostics.error(
                    "argument ${index + 1} of '$name' is declared as ${expected.displayName} but has " +
                        "type ${actual.displayName}",
                    argument.span,
                )
            }
        }
    }

    /**
     * `{ x, y -> yield x + y }`
     *
     * Parameter types come from [expectedType] when the lambda omits them. The result type is the
     * type of the first `yield` in the body, falling back to the expected return type.
     */
    private fun analyzeLambda(expression: LambdaExpression, expectedType: NekoType?): NekoType {
        // A lambda is a *value*, so it satisfies a nullable function type as readily as a plain one:
        // `f: ([int] -> int)? = { x -> ... }` is a real value in a place that may also hold nothing.
        // The parameter types it infers come from the function type underneath.
        val expectedFunction = expectedType?.unwrapNullable() as? FunctionType

        // A lambda also satisfies a *single-method interface* — `Runnable`, `Comparator`, or one the
        // program declares — which is what makes `run({ ... })` work against a Java-style parameter.
        // The interface's one abstract method supplies exactly what a function type would: the
        // parameters and the result the body has to produce. See [singleAbstractMethodOf].
        val samTarget = if (expectedFunction == null) {
            (expectedType?.unwrapNullable() as? ClassType)?.let { singleAbstractMethodOf(it) }
        } else {
            null
        }

        // What the body is checked against, whichever kind of target it was given.
        val expectedParameters: List<NekoType>? = expectedFunction?.parameters ?: samTarget?.parameterTypes
        val expectedReturn: NekoType? = expectedFunction?.returnType ?: samTarget?.returnType

        if (expectedParameters == null && expression.parameters.any { it.declaredType == null }) {
            diagnostics.error(
                "cannot infer the parameter types of this lambda: the surrounding context does not " +
                    "expect a function type, so annotate each parameter, as in " +
                    "'{ x: int, y: int -> ... }'",
                expression.span,
            )
        }
        if (expectedParameters != null && expectedParameters.size != expression.parameters.size) {
            diagnostics.error(
                "this lambda declares ${expression.parameters.size} parameter" +
                    "${if (expression.parameters.size == 1) "" else "s"} but a function type with " +
                    "${expectedParameters.size} parameter" +
                    "${if (expectedParameters.size == 1) "" else "s"} is expected here",
                expression.span,
            )
        }

        val parameterTypes = expression.parameters.mapIndexed { index, parameter ->
            parameter.declaredType?.let { resolveType(it) }
                ?: expectedParameters?.getOrNull(index)
                ?: ErrorType
        }
        expression.resolvedParameterTypes = parameterTypes
        expression.samInterface = samTarget

        val state = LambdaState(identity = Any())
        // The expected function type is already known here, so its result type is what each `yield`
        // is checked against from the start — which is the only way a bare `yield null` can have a
        // type to be absent from. Without this the first `yield` would fix the result type from its
        // own value alone, and a literal whose type only its context can supply would have none.
        //
        // A single-method interface supplies the same fact, so a body written against `Runnable`
        // gets `void` and one written against a supplier gets that method's result.
        state.expectedReturnType = expectedReturn
        state.returnTypeIsDeclared = expectedFunction != null || samTarget != null

        expression.identity = state.identity
        val outerLambda = currentLambda
        val outerFunction = currentFunction
        // The body runs later, from a place of its own, so a `yield` written in it belongs to *this*
        // lambda rather than to a `nya` value form the lambda happens to be written inside. A `nya`
        // inside the body sets its own state while that `nya`'s blocks are analysed, so nesting the
        // other way round still works.
        val outerNya = currentNya
        // A lambda body runs on a different object, so it has no `this`: naming an instance member
        // bare would silently read the wrong object, so the implicit receiver is withdrawn here.
        val outerImplicitThis = implicitThisClass
        // A lambda's body becomes a method of its own, so the locks the enclosing `purr` blocks hold
        // are not held *inside* it as far as nesting goes: taking the same lock there is a real
        // deadlock at run time, but nothing here can prove the lambda is ever called, and a nested
        // `purr` of the same lock within a lambda body is still the reader's mistake to make. What
        // matters is that the enclosing stack is not carried in and then popped twice.
        val enclosingPurrLocks = heldPurrLocks.toList()
        heldPurrLocks.clear()
        currentLambda = state
        // A lambda is its own method, so its locals are stamped with its identity; the code generator
        // captures everything else it names.
        val outerBoundary = currentBoundaryIdentity
        currentBoundaryIdentity = state.identity
        currentNya = null
        // `return` inside a lambda is an error (reported by `analyzeReturn`), so the enclosing
        // function must not stay visible here.
        currentFunction = null
        implicitThisClass = null
        // What is known about a field is known about its value at this point in the *enclosing*
        // method, and the body will run at some later moment instead: a `val` field tested here may
        // have been given something else by then, so the body starts from nothing known about fields.
        // The enclosing facts come back afterwards, because the lambda expression itself is still
        // sitting exactly where they were proved.
        val enclosingFacts = forgetFieldNarrowings()

        pushScope(identifier = state.identity)
        val parameterSymbols = mutableListOf<LocalSymbol>()
        expression.parameters.forEachIndexed { index, parameter ->
            val existing = scopes.last().symbols[parameter.name]
            if (existing != null) {
                diagnostics.error(
                    "lambda parameter '${parameter.name}' is already declared",
                    parameter.nameSpan,
                    notes = listOf(diagnostics.note("previous declaration is here", existing.span)),
                )
                return@forEachIndexed
            }
            val symbol = LocalSymbol(
                name = parameter.name,
                type = parameterTypes[index],
                span = parameter.nameSpan,
                kind = VariableKind.VAL,
                isParameter = true,
                enclosingLambda = state.identity,
            )
            parameterSymbols += symbol
            scopes.last().symbols[parameter.name] = symbol
        }
        expression.parameterSymbols = parameterSymbols

        analyzeBlock(expression.body)

        popScope()
        restoreNarrowings(enclosingFacts)
        currentLambda = outerLambda
        currentBoundaryIdentity = outerBoundary
        currentFunction = outerFunction
        currentNya = outerNya
        implicitThisClass = outerImplicitThis
        heldPurrLocks.clear()
        heldPurrLocks.addAll(enclosingPurrLocks)

        val returnType = state.expectedReturnType ?: when {
            expectedReturn != null -> expectedReturn
            else -> {
                diagnostics.error(
                    "this lambda never yields a value, so its result type cannot be determined; " +
                        "add a 'yield' statement",
                    expression.span,
                )
                ErrorType
            }
        }

        expression.resolvedReturnType = returnType

        // The *value's* type follows the target it was checked against. A single-method interface
        // makes the lambda an instance of that interface, not of a function type — which is what lets
        // it be passed to a parameter declared `Runnable` and what the code generator reads to decide
        // which class to build.
        samTarget?.let { return ClassType(it.jvmName, it.jvmName.substringAfterLast('/')) }
        return FunctionType(parameterTypes, returnType)
    }

    /**
     * The one abstract method of [type], when [type] is a *functional interface*; otherwise `null`.
     *
     * "Functional interface" here means exactly what it means to Java: an interface that declares
     * exactly one abstract method, so a single lambda can be everything an implementer would have to
     * write. `Runnable` and `Comparator` qualify; an interface with two abstract methods does not,
     * because there would be no way to say which one the body belonged to.
     *
     * Two sources have to be consulted, and they are not symmetrical:
     *
     *  * **A Neko interface** has a symbol, so its methods were resolved like any other declaration
     *    and `allFunctions()` walks inherited ones too. A method with a body is a default, so it is
     *    already provided and does not count.
     *  * **A JDK interface** has no symbol — the program never compiles the JDK — so the answer is
     *    read off the loaded class by reflection. Only `abstract` methods count, which is what keeps
     *    `Comparator`'s many `default` and `static` helpers from making it look non-functional.
     *
     * A type that is neither (a class, or an interface with two methods) answers `null`, and the
     * lambda is then checked as it always was, against a function type or not at all.
     */
    private fun singleAbstractMethodOf(type: ClassType): SamTarget? {
        // By internal name: a `ClassType` already carries the package, and two packages may each
        // declare an interface with this simple name.
        val symbol = classes[type.internalName]
        if (symbol != null) {
            if (!symbol.isInterface) return null
            val abstracts = symbol.allFunctions().values
                .flatten()
                .filter { it.isAbstract }
            // Overloads of one name are two abstract methods, so this counts them rather than names.
            if (abstracts.size != 1) return null
            val only = abstracts.single()
            return SamTarget(
                jvmName = symbol.jvmInternalName,
                methodName = only.name,
                parameterTypes = only.parameterTypes,
                returnType = only.returnType,
            )
        }

        // A platform interface, read from the JDK. `java.lang.Runnable` is named `Runnable` here, and
        // a qualified name such as `java.util.Comparator` arrived already resolved as a ClassType with
        // the full internal name, so the load is attempted on the internal name as written.
        val loaded = loadQualifiedClass(type.internalName.replace('/', '.')) ?: return null
        val only = singleAbstractMethodOfPlatform(loaded) ?: return null
        return SamTarget(
            jvmName = type.internalName,
            methodName = only.name,
            parameterTypes = only.parameterTypes.map { typeOfReflected(it) },
            returnType = typeOfReflected(only.returnType),
        )
    }

    /**
     * The one abstract method of a loaded interface, or `null` when [loaded] is not functional.
     *
     * Split out from [singleAbstractMethodOf] because the answer is needed in a second place: choosing
     * between platform constructors of the same arity, where the only fact that can break the tie is
     * that a lambda argument can fit a functional-interface parameter and cannot fit a `String`.
     *
     * Method bodies with a body in an interface are `default` and are not abstract, so they are
     * excluded by the modifier test itself. Bridge and synthetic methods are generated for erasure and
     * are not what an author writes; counting them would make every generic interface look like it
     * had two methods.
     */
    private fun singleAbstractMethodOfPlatform(loaded: Class<*>): java.lang.reflect.Method? {
        if (!loaded.isInterface) return null
        val abstracts = loaded.methods.filter { method ->
            java.lang.reflect.Modifier.isAbstract(method.modifiers) &&
                !method.isSynthetic && !method.isBridge
        }
        return abstracts.singleOrNull()
    }

    /**
     * `value::generic` — the type arguments the value was constructed with, as a `Class[]`.
     *
     * This is the only way to see a type argument at run time, because everything else erases it. The
     * receiver's *static* type is what decides the answer: `Instance<int>` and `Instance<string>`
     * share one class, so the arguments cannot come from the class — they come from the field each
     * construction writes (see [GenericAccessExpression.GENERIC_FIELD_NAME]).
     *
     * A value whose type declares no type parameters has nothing to report, so asking is an error
     * rather than an empty array: an empty array would compile an `Object` receiver into something
     * that silently answers "no arguments", which reads as if the answer were real.
     */
    private fun analyzeGenericAccess(expression: GenericAccessExpression): NekoType {
        val receiverType = analyzeExpression(expression.receiver)

        if (expression.memberName != GenericAccessExpression.GENERIC_MEMBER) {
            diagnostics.error(
                "'::' answers the type arguments of a generic value, so the only name it takes " +
                    "is '${GenericAccessExpression.GENERIC_MEMBER}', but '${expression.memberName}' " +
                    "was written",
                expression.memberSpan,
                unresolved = true,
            )
            return ErrorType
        }

        if (receiverType.isError) return ErrorType

        val classType = receiverType as? ClassType
        if (classType == null) {
            diagnostics.error(
                "'::${GenericAccessExpression.GENERIC_MEMBER}' needs a value of a generic class, but " +
                    "this one is of type ${receiverType.displayName}",
                expression.memberSpan,
            )
            return ErrorType
        }

        // Two sources of truth, in the order that keeps them honest.
        //
        // A class the *program* declares carries the arguments on each object, so the field is what
        // the construction actually named — authoritative even when the declared type disagrees.
        //
        // A class from outside the program (`java.util.ArrayList`) has no such field: it was compiled
        // long ago, and writing one would be a `putfield` against a field that does not exist. But if
        // the *declared type* names the arguments, they are already known here, so the read becomes a
        // constant array. That is reification by the compiler rather than by the class file, and it is
        // the only reading available for a JDK generic.
        val declaration = classBySimpleName(classType.simpleName)
        if (declaration == null) {
            if (classType.arguments.isEmpty()) {
                diagnostics.error(
                    "'${classType.displayName}' declares no type parameters, so it has no type " +
                        "arguments to report",
                    expression.memberSpan,
                )
                return ErrorType
            }
            expression.constantTypeArguments = classType.arguments
            return ArrayType(CLASS_TYPE)
        }

        // Only the value's *own* declaration counts. A subclass of a generic class would carry the
        // parent's tokens, but Neko cannot currently write a generic supertype (`class Sub: Box<int>`
        // parses as an interface list, and `Box<int>()` does not parse at all), so there is no way to
        // pin the arguments and the parent's field would be absent. Refusing here is what keeps
        // `::generic` from answering `null` for a value the language could not have parameterised.
        if (declaration.typeParameters.isEmpty()) {
            diagnostics.error(
                "'${classType.displayName}' declares no type parameters, so it has no type arguments " +
                    "to report",
                expression.memberSpan,
            )
            return ErrorType
        }

        expression.ownerInternalName = declaration.jvmInternalName
        return ArrayType(CLASS_TYPE)
    }

    /**
     * `receiver.member`
     *
     * Reaches a member of an object, which is the only way to use a non-static field or function:
     * those live on an instance, so an object has to exist first. A static member is reachable
     * either way — `Sample.field1` and `obj.field1` both work once an object exists — but writing
     * the class name here is the honest form, so it is accepted as well.
     *
     * Everything is reported at the member name rather than at the whole expression, so the caret
     * lands on the part that is wrong.
     */
    private fun analyzeMemberAccess(expression: MemberAccessExpression): NekoType {
        // `Sample.field1` names a class rather than a value, so it is matched before the receiver is
        // analysed: there is no value there to have a type.
        //
        // A *platform* class is matched here too, and that is not a detail. `classes` holds only what
        // the program declares, so `System.currentTimeMillis()` used to fall through to the value path
        // below, where `System` is a name no scope declares — and the reader was told the name does not
        // exist, although `System` is exactly the class they meant. Since there is no static-import
        // syntax and no qualified type syntax, falling through left no way at all to reach a JDK
        // static member. Resolving the name as a class first is what makes the ordinary spelling work.
        val className = (expression.receiver as? NameExpression)?.name
        val namedClass = className?.let { classNamed(it, expression.memberSpan) }
        if (namedClass != null) {
            if (expression.isSafeCall) reportSafeCallOnClassName(expression.memberSpan, className)
            expression.receiverIsClass = true
            expression.receiver.resolvedType = ClassType(namedClass.jvmInternalName, namedClass.name)
            return staticMemberOf(namedClass, expression, namedClass.name)
        }

        // A name the program does not declare, but the JVM does: `System`, `Math`, `Integer`, or a class
        // from an imported package. It has no symbol — the program never compiled the JDK — so its
        // members cannot be checked one by one. The call form is handled by `analyzeMemberCall`;
        // reaching here means the member was read rather than called, and the only thing that could
        // be read this way is a static field.
        val platformType = platformClassType(className)
        if (platformType != null) {
            if (expression.isSafeCall) reportSafeCallOnClassName(expression.memberSpan, className!!)
            expression.receiverIsClass = true
            expression.receiver.resolvedType = platformType
            return platformStaticFieldOf(platformType, expression)
        }

        // A *qualified* receiver reaching a static field — `java.lang.System.out`. Same ambiguity as
        // in the call path: the chain is plain names, and only resolution can say whether it ends at
        // a class. Asked without reporting, so an ordinary `point.x` read falls through untouched.
        val dotted = qualifiedClassNameOf(expression.receiver)
        if (dotted != null && '.' in dotted) {
            val receiverClass = qualifiedClassOrNull(dotted)
            if (receiverClass != null) {
                if (expression.isSafeCall) reportSafeCallOnClassName(expression.memberSpan, dotted)
                expression.receiverIsClass = true
                expression.receiver.resolvedType = receiverClass
                val symbol = classes[receiverClass.internalName]
                return if (symbol != null) {
                    staticMemberOf(symbol, expression, receiverClass.simpleName)
                } else {
                    platformStaticFieldOf(receiverClass, expression)
                }
            }
        }

        // A qualified chain that reads as a class name but resolved to nothing has already been
        // described by `resolveClassType`; falling through would analyse `java` as a value and add a
        // second, misleading "unresolved reference 'java'".
        //
        // The receiver is the candidate when it already ends in a capitalized segment — `java.lang.Nope`
        // in `java.lang.Nope.f` — because there the member is an ordinary member name. Otherwise the
        // whole dotted text is, since the member is the class itself: `a.b.C` read as a field.
        val failedType = dotted?.let { receiver ->
            if ('.' in receiver && receiver.substringAfterLast('.').firstOrNull()?.isUpperCase() == true) {
                receiver
            } else {
                "$receiver.${expression.memberName}"
            }
        }
        if (failedType != null && '.' in failedType && readsAsQualifiedType(failedType)) {
            resolveClassType(failedType, expression.span)
            return ErrorType
        }

        val receiverType = analyzeExpression(expression.receiver)

        if (receiverType.isError) return ErrorType

        // `?.` is what makes reaching into a nullable value legal, so the two questions are asked of
        // the *same* receiver: what it is when it is there, and whether it might not be.
        val safe = expression.isSafeCall
        val present = receiverType.unwrapNullable()
        if (present !is ClassType) {
            diagnostics.error(
                "cannot access member '${expression.memberName}' on a value of type " +
                    "${receiverType.displayName}, which has no members",
                expression.memberSpan,
            )
            return ErrorType
        }
        if (receiverType is NullableType && !safe) {
            reportUnsafeDereference(
                "cannot access member '${expression.memberName}' on a value of type " +
                    receiverType.displayName,
                safeCallAdvice(expression.receiver.span.text, expression.memberName, ""),
                expression.memberSpan,
            )
            return ErrorType
        }

        val classSymbol = classOf(present)
        val field = classSymbol?.fields?.get(expression.memberName)
        if (field != null) {
            expression.resolvedField = field
            // Reading through `?.` produces `null` when the receiver was `null`, so the member's own
            // type is the *present* answer and the read's type is that or absent. A plain read may
            // still be narrowed: `if (point.x != null) { point.x.f }` reaches the same field through
            // the same value, so what the test proved is what this read has.
            return if (safe) nullableResultOf(field.type) else readFieldTypeOf(field, expression.receiver) ?: field.type
        }

        val functions = classSymbol?.functions?.get(expression.memberName).orEmpty()
        if (functions.isNotEmpty()) {
            // A bare `obj.f` names a function without calling it. Neko has no method references,
            // and the sample never needs one, so say so plainly instead of guessing an overload.
            expression.resolvedFunction = functions.first()
            diagnostics.error(
                "'${expression.memberName}' is a function of ${present.displayName}; write " +
                    "'${expression.receiver.span.text}.${expression.memberName}(...)' to call it",
                expression.memberSpan,
            )
            return ErrorType
        }

        diagnostics.error(
            "${present.displayName} has no member named '${expression.memberName}'",
            expression.memberSpan,
            unresolved = true,
        )
        return ErrorType
    }

    /**
     * [type] as the value a `?.` answers: the member's own type, or that type made nullable when it is
     * not already.
     *
     * A member that is *itself* nullable stays as it is. Wrapping it again would describe a `T??`,
     * which the parser refuses to let anyone write and which would mean nothing extra here anyway:
     * `a?.b` where `b` is a `string?` is absent exactly when either the receiver or the field is.
     */
    private fun nullableResultOf(type: NekoType): NekoType =
        if (type.isError || type === VoidType || type is NullableType) type else NullableType(type)

    /**
     * Reports reaching into a nullable value without `?.`, which is the one mistake the nullable type
     * is there to catch.
     *
     * [prefix] says what was being done — `cannot access member 'x' on a value of type Point?` — and
     * [advice] says what to write instead: the same expression spelled safely, or why there is no safe
     * spelling. The advice is a parameter rather than a fixed sentence because the three dereferences
     * differ: a read and a call have a `?.` form, an index does not, and a *write* has no `?.` form at
     * all — `?.` guards a read — so a reader has to be told something else there.
     */
    private fun reportUnsafeDereference(prefix: String, advice: String, span: Span) {
        diagnostics.error("$prefix, which may be null; $advice", span)
    }

    /**
     * The advice for a read or a call, which is the same sentence with the member spelled differently.
     *
     * A test is *not* offered first, even though smart cast means one would work: the advice has to
     * make sense for every read, and a value that is absent on the path it is written on would only
     * be refused again inside the branch. `?.` always works, and the second clause names the lasting
     * fix — a value that is never absent needs no guard at all.
     */
    private fun safeCallAdvice(receiver: String, member: String, suffix: String): String =
        "write '$receiver?.$member$suffix' to do nothing when it is absent, or give the value a " +
            "non-nullable type"

    /**
     * Reports a `?` written where nothing can be absent — on a class name or a declared function.
     *
     * Both are resolved at compile time rather than held in a value, so there is no null for the `?`
     * to guard and the safe form would quietly mean the ordinary one. Saying so is better than
     * accepting a `?` that does nothing, which would read as if the call might not happen.
     */
    private fun reportRedundantSafeCall(span: Span, reason: String, plain: String) {
        diagnostics.error("'?' is not needed here: $reason; write '$plain' instead", span)
    }

    /** Reports `Cls?.member` or `Cls.member?()`, where the receiver is a class name. */
    private fun reportSafeCallOnClassName(span: Span, className: String) {
        reportRedundantSafeCall(
            span,
            "$className is a class name, which is never null",
            "$className.…",
        )
    }

    /**
     * Whether [dotted] was unmistakably written as a *type* rather than as a value being reached into.
     *
     * A dotted chain is ambiguous in general — `point.x` and `java.util.HashMap` look the same — but
     * two shapes cannot be a value, and both are worth recognising so that a mistake in them is
     * reported once, as the type it was meant to be, instead of decaying into "unresolved reference
     * 'java'":
     *
     *  * it ends in a capitalized segment, which is how a class is spelled;
     *  * it starts at a package the platform describes. `java` and `javax` are names no variable can
     *    hold, and a chain beginning with one is a reader writing a qualified class name.
     *
     * Used only after resolution has already failed, so it never suppresses a legal program.
     */
    private fun readsAsQualifiedType(dotted: String): Boolean {
        // A chain rooted at a class the program declares is a member access: `Code.OK` reads a static
        // field of `Code`. Reporting it as an unknown type would be wrong twice over — the type is
        // fine, and the name is not a type at all.
        if (classBySimpleName(dotted.substringBefore('.')) != null) return false
        if (dotted.substringAfterLast('.').firstOrNull()?.isUpperCase() == true) return true
        val root = dotted.substringBefore('.')
        return root != dotted && (root == "java" || root == "javax" || PlatformLibrary.hasPackage(root))
    }

    /**
     * The class a **qualified** name might denote, or `null` — asking, never reporting.
     *
     * A dotted chain of names is ambiguous by construction: `java.util.HashMap` is a class, while
     * `list.size` and `point.x` are a value and one of its members, and nothing in the syntax tells
     * them apart. Resolution is what decides, and it must decide *before* anything is reported,
     * because the common case — an ordinary member read written with dots — must not be greeted with
     * "unknown type".
     *
     * So this is the question the analyzer may ask freely and repeat: is there a class with exactly
     * this dotted name, either declared by the program or loadable from the platform? Only when the
     * answer is yes does the caller commit to reading the chain as a class name, and only then may
     * `resolveClassType` be called — which is where a *genuinely* bad qualified type is reported.
     */
    private fun qualifiedClassOrNull(dotted: String): ClassType? {
        val simpleName = dotted.substringAfterLast('.')
        if (simpleName.firstOrNull()?.isUpperCase() != true) return null

        // A chain that *starts* at a class the program declares is a member access, not a qualified
        // package name: `Code.OK` is the enum constant `OK` of `Code`, and `point.Distance`-style
        // chains are the same shape. This check is what keeps the rest of this function from
        // inventing a class called `Code/OK` out of a perfectly ordinary static field read.
        val root = dotted.substringBefore('.')
        if (classBySimpleName(root) != null) return null

        // A class the program declares, reachable by its package: `com.example.Helper`. The
        // declaration knows its own internal name, so it is authoritative.
        classes[dotted.replace('.', '/')]?.let { symbol ->
            if (dotted.replace('.', '/') == symbol.jvmInternalName) {
                return ClassType(symbol.jvmInternalName, simpleName)
            }
        }

        val internalName = dotted.replace('.', '/')
        if (loadQualifiedClass(dotted) != null) return ClassType(internalName, simpleName)

        // A class compiled elsewhere, in a package the platform does not describe: it cannot be
        // checked, so a name that *looks* qualified is taken on the file's word. This is the same
        // rule an unqualified name outside `java.lang` has always had, and without it a qualified
        // name would be useless for exactly the case it exists for.
        if (!PlatformLibrary.hasPackage(dotted.substringBeforeLast('.'))) {
            return if (loadPlatformClass(simpleName) == null) ClassType(internalName, simpleName) else null
        }

        // The package is real but holds no such class: `java.util.Hashmap` is a typo, and answering
        // here would turn it into a reference to a class file that does not exist.
        return null
    }

    /**
     * The dotted class name [receiver] spells, or `null` when it is not a chain of plain names.
     *
     * `java.lang.System` parses as `MemberAccess(MemberAccess(Name(java), lang), System)` — which is
     * exactly what a *member access* is, so nothing in the syntax distinguishes the two readings. The
     * difference is what the names turn out to be, and that is what this function lets the caller find
     * out: it reconstructs the dotted text only when every link is a plain name, so a receiver that
     * reads a real value (`list.size`, `obj.field`) is left alone and analysed as the value it is.
     *
     * Returns `null` for anything else, which is the signal to carry on with the ordinary path.
     */
    private fun qualifiedClassNameOf(receiver: Expression): String? = when (receiver) {
        is NameExpression -> receiver.name
        is MemberAccessExpression -> {
            val prefix = qualifiedClassNameOf(receiver.receiver)
            prefix?.let { "$it.${receiver.memberName}" }
        }
        else -> null
    }

    /**
     * A call to a static method of a class named by its **package** — `java.lang.System.exit(0)`.
     *
     * `platformQualifiedCall` is the same work the unqualified path does, but the owner is already
     * settled: a qualified name says where the class is, so there is no imported package to search
     * and no `java.lang` default to fall back on.
     */
    private fun platformQualifiedCall(
        expression: CallExpression,
        callee: MemberAccessExpression,
        owner: ClassType,
    ): NekoType = platformStaticCall(expression, callee, owner)

    /**
     * `Cls.member` / `Cls.member(...)` where `Cls` is a JDK class the program does not declare.
     *
     * There is no symbol for such a class — the program never compiled the JDK — so the members cannot
     * be checked against a declaration. The platform answers instead, through reflection, exactly as
     * [isPlatformClass] already asks it whether the class name exists at all.
     *
     * The result is modelled as a [BuiltinFunction] rather than an ordinary [FunctionSymbol], because
     * that is the hook the code generator already has for "a method the language supplies, dispatched
     * to a fixed owner and descriptor". Nothing here is lowered differently from `println`: the emitter
     * sees an `invokestatic` against `java/lang/System` and does not need to know it came from the JDK.
     *
     * An overload is chosen by arity and by whether each argument's type is assignable to the reflected
     * parameter. Neko has no implicit numeric widening in arguments, so a mismatch is reported rather
     * than guessed at — a wrong guess here would emit a descriptor the JVM rejects at run time.
     */
    private fun platformStaticCall(
        expression: CallExpression,
        callee: MemberAccessExpression,
        owner: ClassType,
    ): NekoType {
        val arguments = expression.arguments.map { analyzeExpression(it) }
        if (arguments.any { it.isError }) return ErrorType

        // A static method only: an instance method needs an object, and this receiver is a class.
        val candidates = runCatching {
            Class.forName(owner.internalName.replace('/', '.'), false, javaClass.classLoader)
                .methods
                .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.name == callee.memberName }
        }.getOrNull().orEmpty()

        if (candidates.isEmpty()) {
            diagnostics.error(
                "${owner.simpleName} has no static member named '${callee.memberName}'",
                callee.memberSpan,
                unresolved = true,
            )
            return ErrorType
        }

        val chosen = candidates.firstOrNull { method ->
            method.parameterCount == arguments.size && method.parameterTypes.withIndex().all { (index, parameter) ->
                argumentFits(arguments[index], parameter)
            }
        }
        if (chosen == null) {
            val wanted = arguments.joinToString(", ") { it.displayName }
            diagnostics.error(
                "${owner.simpleName}.${callee.memberName} has no overload taking ($wanted); it " +
                    "declares ${candidates.joinToString(", ") { "(${it.parameterTypes.joinToString(", ") { p -> p.simpleName }})" }}",
                callee.memberSpan,
            )
            return ErrorType
        }

        val returnType = typeOfReflected(chosen.returnType)
        callee.resolvedFunction = platformFunction(callee, owner, chosen, arguments, returnType)
        expression.instanceMethod = callee.resolvedFunction
        return returnType
    }

    /**
     * Wraps a reflected [method] as the symbol the emitter dispatches on.
     *
     * [parameterTypes] is what the call is *checked and converted against*, and for a JDK method that
     * has to be the reflected signature rather than the argument types: `list.add(1)` passes an `int`
     * where the JDK declares `add(Object)`, and the boxing is the difference between a call the JVM
     * resolves and one it rejects. A static call keeps passing [arguments] because its emitter takes
     * the descriptor from [BuiltinFunction.jvmDescriptor] and never needs the conversion decision.
     */
    private fun platformFunction(
        callee: MemberAccessExpression,
        owner: ClassType,
        method: java.lang.reflect.Method,
        arguments: List<NekoType>,
        returnType: NekoType,
        parameterTypes: List<NekoType> = arguments,
        dispatchAsInterface: Boolean = false,
    ): FunctionSymbol = FunctionSymbol(
        name = callee.memberName,
        span = callee.memberSpan,
        parameterTypes = parameterTypes,
        returnType = returnType,
        enclosingClass = null,
        declaration = null,
        builtin = BuiltinFunction(
            jvmOwner = owner.internalName,
            jvmName = method.name,
            jvmDescriptor = descriptorOfReflected(method),
            receiverIsSystemOut = false,
            dispatchAsInterface = dispatchAsInterface,
        ),
    )

    /**
     * `obj.member(...)` where `obj` is a JDK type the program does not declare.
     *
     * The instance counterpart of [platformStaticCall], and it exists for the same reason: the
     * program never compiled `java.util.ArrayList`, so there is no `ClassSymbol` to check `add`
     * against, and refusing the call told the reader nothing they could act on — the class is real,
     * its method is real, and only the *declaration* is missing. Reflection answers instead.
     *
     * The arguments are converted against the **reflected** parameter types rather than the argument
     * types. That is what makes `add(1)` work: the JDK declares `add(Object)`, so the `int` is boxed
     * on the way in, exactly as a Java call site would.
     */
    private fun platformInstanceCall(
        expression: CallExpression,
        callee: MemberAccessExpression,
        owner: ClassType,
    ): NekoType {
        val arguments = expression.arguments.map { analyzeExpression(it) }
        if (arguments.any { it.isError }) return ErrorType

        val reflected = runCatching {
            Class.forName(owner.internalName.replace('/', '.'), false, javaClass.classLoader)
        }.getOrNull()
        if (reflected == null) {
            diagnostics.error(
                "cannot call '${callee.memberName}': ${owner.displayName} is not declared in this " +
                    "file, so its members cannot be checked",
                callee.memberSpan,
            )
            return ErrorType
        }

        // An instance method only: a static one needs no object, and the receiver here is a value.
        val candidates = reflected.methods.filter {
            !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.name == callee.memberName
        }
        if (candidates.isEmpty()) {
            diagnostics.error(
                "${owner.simpleName} has no member named '${callee.memberName}'",
                callee.memberSpan,
                unresolved = true,
            )
            return ErrorType
        }

        // Two passes, and the order is what keeps overload choice reproducible. An argument that
        // matches a parameter *exactly* is preferred, which is what settles `Thread.sleep(1L)` on
        // `sleep(long)` instead of `sleep(Duration)` no matter what order reflection reports them in.
        // Only when nothing matches exactly is boxing allowed — `list.add(1)` against `add(Object)` —
        // because that is a conversion Neko performs everywhere else rather than a guess.
        //
        // When the receiver names type arguments, the program has said more than the erased signature
        // does, and the extra thing has to be honoured: `add` takes `E`, `E` erases to `Object`, and
        // `Object` accepts every value there is — so `ArrayList<int>.add("awa")` passed the erased
        // check and was then boxed into a slot the descriptor calls `Object` while every `get`
        // promises an `int`. The written question is asked of the *candidates*, not in place of the
        // erased ones: the two passes above still decide which overload is emitted, so `remove(0)` on
        // a `List<int>` stays `remove(int)` — its `Object` overload takes the argument just as
        // happily, and settling that by reflection's ordering is the hazard the passes exist to
        // remove. Filtering first and passing over the survivors keeps both properties.
        val bound = boundTypeArguments(reflected, owner)
        val eligible = candidates.filter { method ->
            bound == null || platformArgumentsFit(method, arguments, strict = true, substituted = bound)
        }
        val chosen = eligible.firstOrNull { method -> platformArgumentsFit(method, arguments, strict = true) }
            ?: eligible.firstOrNull { method -> platformArgumentsFit(method, arguments, strict = false) }
        if (chosen == null) {
            // Distinguish "no overload of this shape" from "the shape is there but the receiver's own
            // type arguments refuse the value": the second is the mistake the code actually made, and
            // telling the programmer to look for another overload would send them nowhere.
            if (bound != null) {
                val erased = candidates.firstOrNull { platformArgumentsFit(it, arguments, strict = true) }
                    ?: candidates.firstOrNull { platformArgumentsFit(it, arguments, strict = false) }
                if (erased != null) {
                    reportWrittenArgumentMismatch(expression, callee, erased, arguments, bound)
                    return ErrorType
                }
            }
            val wanted = arguments.joinToString(", ") { it.displayName }
            diagnostics.error(
                "${owner.simpleName}.${callee.memberName} has no overload taking ($wanted); it " +
                    "declares ${candidates.joinToString(", ") { "(${it.parameterTypes.joinToString(", ") { p -> p.simpleName }})" }}",
                callee.memberSpan,
            )
            return ErrorType
        }

        val returnType = typeOfReflected(chosen.returnType)
        val symbol = platformFunction(
            callee = callee,
            owner = owner,
            method = chosen,
            arguments = arguments,
            returnType = returnType,
            parameterTypes = chosen.parameterTypes.map { typeOfReflected(it) },
            dispatchAsInterface = reflected.isInterface,
        )
        callee.resolvedFunction = symbol
        callee.resolvedType = symbol.type
        // The receiver is a value here — that is the whole difference from `platformStaticCall` — so
        // the emitter evaluates it and dispatches on it. Recorded on the call so `emitCall` sees the
        // same shape a Neko instance call records.
        expression.instanceReceiver = callee.receiver
        expression.instanceMethod = symbol
        return returnType
    }

    /**
     * Whether every argument of [method] is acceptable, in one of the three strengths the caller
     * needs.
     *
     * The strict pass is [argumentFits] alone: a primitive must be the parameter's own box, which is
     * what keeps overload selection independent of reflection's ordering. The relaxed pass exists for
     * `list.add(1)`, where the JDK declares `add(Object)` and the `int` has to be boxed on the way in
     * — the same conversion `x: Object = 1` already performs.
     *
     * Both of those read the **erased** signature, because that is what the call is emitted against.
     * [substituted] asks the other question — did the program mean this argument for this slot? — by
     * reading `genericParameterTypes` and replacing each type variable with the argument the receiver
     * was written with. `ArrayList<int>` therefore holds `add` to `int` rather than to the `Object`
     * its `E` erases to, and `add("awa")` is refused instead of quietly unboxed to a lie. The
     * comparison there is [isAssignable] rather than [argumentFits] because both sides are *written*
     * types, not a value against a JVM slot: no boxing is being decided, only whether the program
     * meant this. A parameter naming no type variable is compared as itself, and one whose variable
     * cannot be resolved is passed over rather than refused — silence beats a wrong complaint.
     *
     * That third strength **narrows** the field; it never picks the winner. The caller filters the
     * candidates with it and then runs the other two over the survivors, so a method that survives
     * the written check still competes on its erased signature. `remove(0)` on a `List<int>` is why:
     * `remove(int)` and `remove(Object)` both fit an `int` written against an `E`, and only the strict
     * pass prefers the exact one. Answering with the substituted pass alone would hand that decision
     * back to reflection's ordering.
     *
     * Relaxing is only sound as far as **boxing** goes, so it is bounded by the box itself: the
     * parameter must be able to hold `Integer` for an `int` argument, and not merely be a reference.
     * That distinction is what stops `f(1L)` from binding to a `Duration` parameter when nothing
     * matched exactly — `Duration` holds no `Long`, and emitting that call would box a `long` onto a
     * stack slot the verifier expects to be a `Duration`, which is exactly the class-writer failure
     * the strict pass was introduced to prevent. Because the relaxed pass only runs when the strict
     * one found nothing, no overload choice depends on reflection's ordering.
     */
    private fun platformArgumentsFit(
        method: java.lang.reflect.Method,
        arguments: List<NekoType>,
        strict: Boolean,
        substituted: Map<java.lang.reflect.TypeVariable<*>, NekoType>? = null,
    ): Boolean {
        if (method.parameterCount != arguments.size) return false
        if (substituted != null) {
            return method.genericParameterTypes.withIndex().all { (index, parameter) ->
                val declared = substitutedTypeOf(parameter, substituted) ?: return@all true
                isAssignable(arguments[index], declared)
            }
        }
        return method.parameterTypes.withIndex().all { (index, parameter) ->
            val argument = arguments[index]
            if (argumentFits(argument, parameter)) return@all true
            if (strict) return@all false
            val box = boxOf(argument) ?: return@all false
            parameter.isAssignableFrom(box)
        }
    }

    /**
     * The type arguments [owner] was written with, keyed by the type variables [reflected] declares.
     *
     * `null` when the receiver names none — the erased spelling `ArrayList` — because then the
     * program has said nothing to hold the call to, and the erased signature is all there is.
     */
    private fun boundTypeArguments(
        reflected: Class<*>,
        owner: ClassType,
    ): Map<java.lang.reflect.TypeVariable<*>, NekoType>? {
        val declared = reflected.typeParameters
        if (owner.arguments.isEmpty() || declared.size != owner.arguments.size) return null
        return declared.zip(owner.arguments).toMap()
    }

    /**
     * The type a reflected [type] describes once [bound] replaces the type variables with what the
     * program wrote, or `null` when that cannot be said — a method's own `<U>`, a wildcard, an
     * array whose component depends on one.
     *
     * A method inherited from a generic superclass names *that* class' variable — `ArrayList` and
     * `AbstractList` each declare an `E`, and they are different `TypeVariable`s — so a variable
     * missing from the map is matched by name before giving up. `ArrayList<int>` binds both to
     * `int`, which is what the inherited call site meant just as much as the declared one.
     */
    private fun substitutedTypeOf(
        type: java.lang.reflect.Type,
        bound: Map<java.lang.reflect.TypeVariable<*>, NekoType>,
    ): NekoType? {
        if (type is java.lang.reflect.TypeVariable<*>) {
            bound[type]?.let { return it }
            return bound.entries.firstOrNull { it.key.name == type.name }?.value
        }
        if (type is Class<*>) return typeOfReflected(type)
        if (type is java.lang.reflect.ParameterizedType) {
            val raw = type.rawType as? Class<*> ?: return null
            val bare = typeOfReflected(raw) as? ClassType ?: return null
            val arguments = type.actualTypeArguments.map { substitutedTypeOf(it, bound) ?: return null }
            return parameterisedType(bare, arguments)
        }
        return null
    }

    /**
     * Reports the argument that breaks the receiver's own type arguments, if one does.
     *
     * Only the caller's [chosen] overload is described, and only after every candidate was found to
     * disagree with the written arguments — so the sentence names the call the code generator would
     * have emitted, and one bad argument does not get blamed on an overload that another argument
     * had already ruled out.
     */
    private fun reportWrittenArgumentMismatch(
        expression: CallExpression,
        callee: MemberAccessExpression,
        chosen: java.lang.reflect.Method,
        arguments: List<NekoType>,
        bound: Map<java.lang.reflect.TypeVariable<*>, NekoType>,
    ) {
        for ((index, parameter) in chosen.genericParameterTypes.withIndex()) {
            val expected = substitutedTypeOf(parameter, bound) ?: continue
            val actual = arguments.getOrNull(index) ?: continue
            if (isAssignable(actual, expected)) continue
            val declared = (parameter as? java.lang.reflect.TypeVariable<*>)?.name ?: expected.displayName
            diagnostics.error(
                "argument ${index + 1} of '${callee.memberName}' is declared as $declared, which " +
                    "is ${expected.displayName} here, but has type ${actual.displayName}",
                expression.arguments.getOrNull(index)?.span ?: callee.memberSpan,
            )
            return
        }
    }

    /**
     * Whether a value of Neko type [argument] may be passed where the JVM declares [parameter].
     *
     * A primitive parameter only ever takes its own type: Neko has no implicit numeric widening, so
     * `sleep(1)` is *not* an argument for `sleep(long)` here and is reported rather than guessed at.
     *
     * A reference parameter takes:
     *
     *  * any reference value, because the emitter inserts whatever conversion is needed and the
     *    exact class is not this check's business;
     *  * a primitive value only when the parameter is that primitive's **own** box (`Integer` for
     *    `int`), because that is the one reference a primitive can become without changing meaning.
     *
     * That second rule is not fussiness — it is a correctness fix. `Thread` declares both
     * `sleep(long)` and `sleep(Duration)`, and reflection returns them in **no fixed order**: JDK 21
     * lists `sleep(Duration)` first, JDK 25 lists `sleep(long)` first. Under a rule of "a reference
     * parameter accepts anything", `Thread.sleep(1L)` therefore chose `sleep(Duration)` on JDK 21 and
     * `sleep(long)` on JDK 25 — the same program, a different descriptor. The wrong one pushes a
     * `long` where a `Duration` is declared, and the class writer then fails computing stack-map
     * frames (`ArrayIndexOutOfBoundsException` inside ASM's `Frame.merge`), because the operand stack
     * no longer matches what the code put there. Choosing overloads by a rule that does not depend on
     * reflection's ordering is what makes compilation reproducible across JDKs.
     */
    private fun argumentFits(argument: NekoType, parameter: Class<*>): Boolean {
        val primitive = parameterPrimitive(parameter)
        if (primitive != null) return argument == primitive
        if (argument === VoidType) return false
        val box = boxOf(argument)
        return box == null || box == parameter
    }

    /**
     * The JDK class a Neko value boxes to, or `null` when it is already a reference type.
     *
     * This is the wrapper `Integer.valueOf` and friends return, spelled as the `Class` object so it
     * can be compared with a reflected parameter type directly.
     *
     * `string`, `Object` and `Throwable` answer `null` because they are references already: a
     * `String` parameter is satisfied by a `string` without any conversion at all.
     */
    private fun boxOf(argument: NekoType): Class<*>? = when (argument) {
        IntType -> java.lang.Integer::class.java
        LongType -> java.lang.Long::class.java
        ShortType -> java.lang.Short::class.java
        ByteType -> java.lang.Byte::class.java
        CharType -> java.lang.Character::class.java
        FloatType -> java.lang.Float::class.java
        DoubleType -> java.lang.Double::class.java
        BoolType -> java.lang.Boolean::class.java
        else -> null
    }

    /** The Neko type a reflected [type] corresponds to. */
    private fun typeOfReflected(type: Class<*>): NekoType {
        val primitive = parameterPrimitive(type)
        if (primitive != null) return primitive
        if (type == java.lang.Void.TYPE) return VoidType
        // A built-in reference type has to be the canonical instance, not a fresh `ClassType` that
        // merely names the same class. The emitter decides boxing by *identity* — `ObjectType` is
        // what a primitive boxes into — so a `ClassType("java/lang/Object")` built here would look
        // like a different type and `list.add(1)` would reach the JVM with a primitive on a stack
        // slot declared `Object`.
        return referenceBuiltinOf(type) ?: ClassType(type.name.replace('.', '/'), type.simpleName)
    }

    /**
     * The canonical built-in instance standing for the reflected [type], or `null` for any other
     * class.
     *
     * Only the three reference built-ins answer, and the wrapper classes deliberately do **not**,
     * even though [BuiltinTypes.byName] maps `Integer` to `IntType`. That table answers a different
     * question: which type a program *writes*. A program that writes `x: Integer` means an `int`,
     * because Neko has no separate wrapper types. Reflection is the other direction — it reports what
     * the class file says — and there `java.lang.Integer` is a reference. `Integer.valueOf(7)`
     * returns the wrapper, the stack holds a reference, and typing that result `int` would tell the
     * emitter to treat it as a primitive: no unboxing would be inserted, and the next call on the
     * value would reach the JVM with a reference where the descriptor promised an `int`. The class is
     * therefore left as a [ClassType], which is what it is.
     *
     * `String`, `Object` and `Throwable` are the opposite case: they *are* the classes those types
     * are carried as, so no conversion is wanted and identity is exactly right — and required, since
     * the emitter compares `ObjectType` by identity to decide boxing.
     */
    private fun referenceBuiltinOf(type: Class<*>): NekoType? = when (type.name) {
        "java.lang.String" -> StringType
        "java.lang.Object" -> ObjectType
        "java.lang.Throwable" -> ThrowableType
        else -> null
    }

    /** The Neko primitive matching a JVM primitive class, or `null` when [type] is a reference. */
    private fun parameterPrimitive(type: Class<*>): NekoType? = when (type) {
        java.lang.Integer.TYPE -> IntType
        java.lang.Long.TYPE -> LongType
        java.lang.Short.TYPE -> ShortType
        java.lang.Byte.TYPE -> ByteType
        java.lang.Character.TYPE -> CharType
        java.lang.Float.TYPE -> FloatType
        java.lang.Double.TYPE -> DoubleType
        java.lang.Boolean.TYPE -> BoolType
        else -> null
    }

    /** The JVM descriptor of [method], which is what the emitter passes to `visitMethodInsn`. */
    private fun descriptorOfReflected(method: java.lang.reflect.Method): String = buildString {
        append('(')
        for (parameter in method.parameterTypes) append(descriptorOfReflected(parameter))
        append(')')
        append(descriptorOfReflected(method.returnType))
    }

    private fun descriptorOfReflected(type: Class<*>): String = when (type) {
        java.lang.Integer.TYPE -> "I"
        java.lang.Long.TYPE -> "J"
        java.lang.Short.TYPE -> "S"
        java.lang.Byte.TYPE -> "B"
        java.lang.Character.TYPE -> "C"
        java.lang.Float.TYPE -> "F"
        java.lang.Double.TYPE -> "D"
        java.lang.Boolean.TYPE -> "Z"
        java.lang.Void.TYPE -> "V"
        else -> "L" + type.name.replace('.', '/') + ";"
    }

    /**
     * `Cls.field` where `Cls` is a JDK class — a static field read through a class name.
     *
     * `System.out` is the one every Neko program meets, because `println` is modelled on it. The field
     * is looked up reflectively and its type taken from the JVM, so a program can read any JDK static
     * field without the compiler carrying a table.
     */
    private fun platformStaticFieldOf(owner: ClassType, expression: MemberAccessExpression): NekoType {
        val field = runCatching {
            Class.forName(owner.internalName.replace('/', '.'), false, javaClass.classLoader)
                .fields
                .firstOrNull {
                    java.lang.reflect.Modifier.isStatic(it.modifiers) && it.name == expression.memberName
                }
        }.getOrNull()

        if (field == null) {
            diagnostics.error(
                "${owner.simpleName} has no static member named '${expression.memberName}'",
                expression.memberSpan,
                unresolved = true,
            )
            return ErrorType
        }

        // A field read is emitted from the field's owner and name, which the AST records here. Only a
        // program-declared field has a symbol, so the JDK case is carried by the receiver's type plus
        // the member name — see `emitStaticFieldRead`.
        expression.resolvedPlatformFieldOwner = owner.internalName
        expression.resolvedPlatformFieldIsStatic = true
        return typeOfReflected(field.type)
    }

    /**
     * Resolves `Cls.member` — a `static` field reached without an object.
     *
     * Only static members can be reached this way, which mirrors the rule in the other direction: an
     * instance member needs an object even when its class is named explicitly.
     */
    private fun staticMemberOf(
        classSymbol: ClassSymbol,
        expression: MemberAccessExpression,
        className: String,
    ): NekoType {
        val field = classSymbol.fields[expression.memberName]
        if (field != null) {
            if (!field.isStatic) {
                diagnostics.error(
                    "field '${field.name}' is an instance member of $className, so it cannot be " +
                        "read from the class; create an object first and write 'obj.${field.name}'",
                    expression.memberSpan,
                )
            }
            expression.resolvedField = field
            // A static field is one place, so `if (Holder.only != null)` narrows every later read of
            // it — including a bare `only` inside the class itself, which is the same storage.
            return narrowings[field] ?: field.type
        }

        val functions = classSymbol.functions[expression.memberName].orEmpty()
        if (functions.isNotEmpty()) {
            expression.resolvedFunction = functions.first()
            diagnostics.error(
                "'${expression.memberName}' is a function of $className; write " +
                    "'$className.${expression.memberName}(...)' to call it",
                expression.memberSpan,
            )
            return ErrorType
        }

        diagnostics.error(
            "$className has no member named '${expression.memberName}'",
            expression.memberSpan,
            unresolved = true,
        )
        return ErrorType
    }

    // ------------------------------------------------------------ name lookup

    /** Resolves a bare identifier, reporting an unresolved reference when nothing matches. */
    private fun resolveName(expression: NameExpression): Symbol? {
        val symbol = lookup(expression.name)
        if (symbol == null) {
            // A name that the program *does* declare, just not where this file can see it, is a
            // different mistake from a typo — and the fix is a line the author can be told to write.
            // Reported first so `Holder.shared` in another package says "import it" rather than
            // "unresolved", which is what the IDE's quick fix keys on.
            importNeededFor(expression.name)?.let { clause ->
                diagnostics.error(
                    "unresolved reference '${expression.name}': it is declared in another package " +
                        "this file does not import — write 'import $clause' to reach it",
                    expression.span,
                    suggestedImport = clause,
                    unresolved = true,
                )
                return null
            }
            diagnostics.error(
                "unresolved reference '${expression.name}'",
                expression.span,
                unresolved = true,
            )
            return null
        }
        if (!checkMemberReachable(symbol, expression.span)) return symbol
        expression.resolution = symbol
        return symbol
    }

    /**
     * Whether a bare reference to [symbol] is legal here, given whether the enclosing function is
     * static and whether an implicit `this` is available.
     *
     * A field or function declared `static` belongs to the class, so a method may name it directly.
     * A non-static one belongs to an instance: inside another non-static member, `field1` means
     * `this.field1` and is fine; inside a static one there is no instance to mean, so the reader must
     * say which object they mean.
     */
    private fun checkMemberReachable(symbol: Symbol, span: Span): Boolean {
        val isInstanceMember = when (symbol) {
            is FieldSymbol -> !symbol.isStatic
            is FunctionSymbol -> !symbol.isStatic && symbol.builtin == null
            else -> false
        }
        if (!isInstanceMember) return true

        if (implicitThisClass != null) return true

        // A field always records the class that declares it. A function's class is null only for a
        // built-in, and no built-in is an instance member, so this branch is only reached for a
        // declared function — where the class is present.
        val owner = when (symbol) {
            is FieldSymbol -> symbol.enclosingClass.name
            is FunctionSymbol -> symbol.enclosingClass?.name
            else -> null
        }
        val what = if (symbol is FieldSymbol) "field" else "function"
        diagnostics.error(
            "$what '${symbol.name}' is an instance member of ${owner ?: "its class"}, so it cannot " +
                "be used from a static context; create an object first and write 'obj.${symbol.name}'",
            span,
        )
        return false
    }

    /** Looks a name up from the innermost scope outwards, then among the built-ins. */
    private fun lookup(name: String): Symbol? {
        for (scope in scopes.reversed()) {
            scope.symbols[name]?.let { return it }
        }
        return builtins[name]?.firstOrNull()
    }

    /**
     * The declared class behind [type], or `null` for `ErrorType` and for classes from other files.
     *
     * A type declared in this file always has a symbol; one that lives outside the compilation unit
     * does not, which is how the analyzer knows it cannot check members against it.
     */
    private fun classOf(type: NekoType): ClassSymbol? =
        (type as? ClassType)?.let { classes[it.internalName] }

    private fun packagePrefix(): String = currentClass?.packagePrefix ?: ""

    // ---------------------------------------------------------------- narrowing

    /**
     * A `val` field read through one particular value: `point.x`.
     *
     * The field alone is not the place the test was about — `left.x` and `right.x` are two
     * different places — so the key pairs the field with the value it was reached through. That
     * pairing is what lets `if (point.x != null) { point.x }` be remembered without assuming
     * anything about `point` itself.
     */
    private data class FieldFact(val field: FieldSymbol, val receiver: Any)

    /**
     * The key a fact about the value [expression] reads is filed under, or `null` when this is not
     * a stored value a test could pin down.
     */
    private fun narrowingKeyOf(expression: Expression): Any? = when (expression) {
        is NameExpression -> narrowingKeyOf(expression.resolution)
        is MemberAccessExpression -> {
            val field = expression.resolvedField ?: return null
            fieldFactKeyOf(field, expression.receiver)
        }
        else -> null
    }

    /**
     * The key a fact about [symbol] is filed under, or `null` when nothing may be assumed of it.
     *
     * A local is always a key, even a `var` one: nothing outside its own function can write it, so
     * dropping the fact at every write the analyzer *does* see is enough on its own — and that is
     * what lets a *parameter* be narrowed at all, since a parameter reports itself mutable. A field
     * is a key only when it was declared with `val`. Any other method could write a `var` field
     * between the test and the read, and the analyzer would never see the write.
     */
    private fun narrowingKeyOf(symbol: Symbol?): Any? = when (symbol) {
        is LocalSymbol -> symbol
        is FieldSymbol -> if (symbol.isMutable) null else symbol
        else -> null
    }

    /**
     * The key a fact about [field] reached through [receiver] is filed under.
     *
     * A static field lives on the class rather than on a value, and `this.x` names the same storage
     * a bare `x` does, so both are keyed by the field alone. Any other receiver names a *particular*
     * object, and two reads are the same place only when they are reached through the same value.
     */
    private fun fieldFactKeyOf(field: FieldSymbol, receiver: Expression): Any? {
        if (field.isMutable) return null
        if (field.isStatic || receiver is ThisExpression) return field
        val owner = narrowingKeyOf(receiver) ?: return null
        return FieldFact(field, owner)
    }

    /**
     * The type a read of [symbol] has at this point in the program.
     *
     * A narrowing changes *this read*, not the declaration: `symbol.type` is still `T?` everywhere
     * the proof does not hold, which is what keeps the fact a statement about one path rather than
     * about the variable. A symbol that cannot be narrowed simply never has a fact.
     */
    private fun readTypeOf(symbol: Symbol?): NekoType {
        if (symbol == null) return ErrorType
        val key = narrowingKeyOf(symbol) ?: return symbol.type
        return narrowings[key] ?: symbol.type
    }

    /** The type a read of [field] through [receiver] has here, or `null` when it is not narrowed. */
    private fun readFieldTypeOf(field: FieldSymbol, receiver: Expression): NekoType? =
        fieldFactKeyOf(field, receiver)?.let { narrowings[it] }

    /**
     * The type [expression] was *declared* with, ignoring any narrowing proved about it.
     *
     * This is the type a question about the declaration is answered from — "can this value be
     * absent at all?" — and it is why a value already narrowed to `Cat` may still be compared
     * against `null`: the suffix is part of the declaration, and a test does not remove it.
     */
    private fun declaredTypeOf(expression: Expression): NekoType = when (expression) {
        is NameExpression -> expression.resolution?.type ?: expression.type
        // A safe read declares itself nullable whatever the field holds — `obj?.x` is the field's type
        // made absent — so the field's own type is the answer only for a plain read.
        is MemberAccessExpression -> if (expression.isSafeCall) {
            expression.type
        } else {
            expression.resolvedField?.type ?: expression.type
        }
        else -> expression.type
    }

    /**
     * What is true when [condition] turns out to be *true*, as a map from the key of a stored value
     * to the type the test proved it has.
     *
     * Only one shape proves anything. `x != null` proves `x` present, and that is the whole of what
     * a `T?` test can establish: there is no type meaning "is null" for the other side to narrow to.
     * The two connecting operators carry the facts the way their own truth does — `a && b` being
     * true means both sides held, so the facts are the two sets together, while `a || b` being true
     * means only that *one* of them held, so the only facts left standing are the ones both sides
     * agree on.
     */
    private fun factsTrue(condition: Expression): Map<Any, NekoType> = when {
        condition is UnaryExpression && condition.operator == UnaryOperator.NOT ->
            factsFalse(condition.operand)

        condition is BinaryExpression && condition.operator == BinaryOperator.AND ->
            combineFacts(factsTrue(condition.left), factsTrue(condition.right))

        condition is BinaryExpression && condition.operator == BinaryOperator.OR ->
            commonFacts(factsTrue(condition.left), factsTrue(condition.right))

        else -> presentFact(condition, whenPresent = true)
    }

    /**
     * What is true when [condition] turns out to be *false*.
     *
     * The mirror of [factsTrue]: `x == null` failing is what proves `x` present, and the operators
     * swap roles, because failing a conjunction tells us about only *one* of its sides.
     */
    private fun factsFalse(condition: Expression): Map<Any, NekoType> = when {
        condition is UnaryExpression && condition.operator == UnaryOperator.NOT ->
            factsTrue(condition.operand)

        condition is BinaryExpression && condition.operator == BinaryOperator.AND ->
            commonFacts(factsFalse(condition.left), factsFalse(condition.right))

        condition is BinaryExpression && condition.operator == BinaryOperator.OR ->
            combineFacts(factsFalse(condition.left), factsFalse(condition.right))

        else -> presentFact(condition, whenPresent = false)
    }

    /**
     * The fact a bare null test gives, when that test's outcome is [whenPresent].
     *
     * `x != null` proves `x` present when it holds; `x == null` proves the same thing when it
     * *fails*. The two other combinations prove nothing, and neither does a comparison against
     * anything but the `null` literal — which is why the form is read off the syntax rather than
     * from the two types, exactly as the equality check itself reads it.
     */
    private fun presentFact(condition: Expression, whenPresent: Boolean): Map<Any, NekoType> {
        if (condition !is BinaryExpression) return emptyMap()
        val operator = condition.operator
        if (operator != BinaryOperator.EQUAL && operator != BinaryOperator.NOT_EQUAL) return emptyMap()
        if ((operator == BinaryOperator.NOT_EQUAL) != whenPresent) return emptyMap()
        val tested = when {
            condition.left is NullLiteral && condition.right !is NullLiteral -> condition.right
            condition.right is NullLiteral && condition.left !is NullLiteral -> condition.left
            else -> return emptyMap()
        }
        val key = narrowingKeyOf(tested) ?: return emptyMap()
        val present = tested.type.unwrapNullable()
        if (present.isError) return emptyMap()
        return mapOf(key to present)
    }

    /**
     * Everything both sets of facts establish.
     *
     * This is the rule for a true `&&` and for a false `||`: both readings being known to hold means
     * both sets do. A key proved by both keeps the tighter of the two types, which is what makes a
     * doubly-narrowed value stay at the type the more specific test gave it.
     */
    private fun combineFacts(first: Map<Any, NekoType>, second: Map<Any, NekoType>): Map<Any, NekoType> {
        if (first.isEmpty()) return second
        if (second.isEmpty()) return first
        val combined = LinkedHashMap(first)
        for ((key, type) in second) {
            val existing = combined[key]
            if (existing == null || type.isAssignableTo(existing)) combined[key] = type
        }
        return combined
    }

    /**
     * The facts both sets agree on, which is all a true `||` and a false `&&` leave standing: when
     * only one side is known to have held, the other side's facts cannot be assumed.
     */
    private fun commonFacts(first: Map<Any, NekoType>, second: Map<Any, NekoType>): Map<Any, NekoType> {
        if (first.isEmpty() || second.isEmpty()) return emptyMap()
        val common = LinkedHashMap<Any, NekoType>()
        for ((key, type) in first) {
            if (second[key] == type) common[key] = type
        }
        return common
    }

    /**
     * The facts in force, as a construct that forks the flow found them, together with the number of
     * writes each was proved under.
     *
     * The two belong together. A construct that forks the flow puts the facts back when it is done,
     * but a write that happened inside one of its arms may or may not have run — so a fact that arm
     * invalidated must not be restored, or `if (c) { h = null }` would leave `h` looking present.
     * Counting writes is how the snapshot can tell a fact that is simply being restored from one
     * that has since been overwritten.
     */
    private class NarrowingSnapshot(
        val facts: Map<Any, NekoType>,
        val writes: Map<Any, Int>,
    )

    /**
     * How many times each key has been written since the analyzer started.
     *
     * Only ever increases, and is never rolled back by a restore: a write that *may* have run still
     * invalidates the fact, which is exactly the conservative answer a merge needs.
     */
    private val writeCounts = HashMap<Any, Int>()

    /** The facts in force, so that a construct which forks the flow can put them back afterwards. */
    private fun saveNarrowings(): NarrowingSnapshot = NarrowingSnapshot(
        LinkedHashMap(narrowings),
        narrowings.keys.associateWith { writeCounts[it] ?: 0 },
    )

    private fun restoreNarrowings(saved: NarrowingSnapshot) {
        narrowings.clear()
        for ((key, type) in saved.facts) {
            if ((writeCounts[key] ?: 0) == saved.writes[key]) narrowings[key] = type
        }
    }

    private fun applyFacts(facts: Map<Any, NekoType>) {
        narrowings.putAll(facts)
    }

    /** Drops every fact [key] holds and records that a write has happened to it. */
    private fun invalidate(key: Any) {
        narrowings.remove(key)
        writeCounts[key] = (writeCounts[key] ?: 0) + 1
    }

    /**
     * Drops every fact a write to [symbol] invalidates.
     *
     * The symbol's own fact goes, and so do the facts about fields reached *through* it: after
     * `point = other`, `point.x` is a different place, so what was proved of the old one says
     * nothing about the new one.
     */
    private fun forgetNarrowingsOf(symbol: Symbol?) {
        if (symbol == null) return
        val reached = narrowings.keys.filter { it is FieldFact && it.receiver == symbol }
        invalidate(symbol)
        reached.forEach { invalidate(it) }
    }

    /** Drops every fact about [field] itself, for a write to it. */
    private fun forgetNarrowingsOfField(field: FieldSymbol) {
        val reached = narrowings.keys.filter { it is FieldFact && it.field == field }
        invalidate(field)
        reached.forEach { invalidate(it) }
    }

    /**
     * Forgets every fact, because a new function body is about to be analysed.
     *
     * Locals do not outlive their function, but a `val` *field* does: a fact proved about one in
     * `main` would otherwise still be in force in the next method, where nothing has tested it.
     */
    private fun resetNarrowings() {
        narrowings.clear()
        writeCounts.clear()
    }

    /**
     * Drops every fact about a field, keeping the ones about names, and returns the snapshot that
     * [restoreNarrowings] wants.
     *
     * A lambda body is where this matters. A local is captured *by value* at the point the lambda
     * object is made, so a fact proved there is a fact about the captured value and may be used in
     * the body. A field is not captured at all — the body reads it when the lambda is *called*,
     * which may be long afterwards and long after the test that proved anything about it.
     */
    private fun forgetFieldNarrowings(): NarrowingSnapshot {
        val incoming = saveNarrowings()
        narrowings.keys.removeAll { it is FieldSymbol || it is FieldFact }
        return incoming
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Resolves a syntactic type to a semantic [NekoType], reporting unknown names precisely.
     *
     * An unknown name is *not* automatically an error: Neko lets a file name a class it does not
     * declare, in its own package, so that `CustomException` and `OtherException` work without a
     * declaration to attach them to. Only a name that is neither a built-in nor a legal class name
     * is rejected.
     */
    private fun resolveType(reference: TypeReference): NekoType = when (reference) {
        is NamedTypeReference -> when (reference.name) {
            // The built-in types, by either spelling: `Integer` is another way to write `int`, so a
            // program may use whichever name matches the Java type it is thinking of, and the two
            // are one type rather than two that would fail to be assignable to each other. This is
            // checked before any class lookup, because every one of these names is also a real JDK
            // class -- without it, `x: String = "hi"` would resolve `String` to `java.lang.String`
            // and then reject the string.
            in BuiltinTypes.byName -> BuiltinTypes.byName.getValue(reference.name)

            else -> {
                // A type parameter of the class being analysed stands for "whatever the caller
                // passes", which is exactly `Object` once the JVM erases it. That is all the
                // standard library's extractors need, since their bodies only forward values.
                //
                // A generic *function's* parameter means the same thing and is checked first, because
                // it is the nearer declaration: `fun <T> f(value: T)` may be written inside
                // `class Box<T>`, and the body's `T` is the function's.
                val owner = currentClass
                if (
                    reference.name in currentTypeParameters ||
                    (owner != null && reference.name in owner.typeParameters)
                ) {
                    ObjectType
                } else {
                    // The arguments are kept on the type rather than discarded with the erasure:
                    // `for ... in` reads them to know what an iterable yields. They are resolved here
                    // so a mistake inside `<...>` is reported like any other bad type.
                    val resolved = resolveClassType(reference.name, reference.nameSpan)
                    val arguments = reference.arguments.map { resolveType(it) }
                    // Only a class may carry arguments; a name that resolved to something else (an
                    // error type, say) keeps the arguments on the floor rather than failing here.
                    if (arguments.isEmpty() || resolved !is ClassType) {
                        resolved
                    } else {
                        parameterisedType(resolved, arguments)
                    }
                }
            }
        }

        // `string?` is `string` plus permission to be absent, and it is the *only* type `null` may
        // be assigned to: wrapping is the whole of what the suffix means, so the inner type is
        // resolved exactly as it would be without it.
        is NullableTypeReference -> resolveNullableType(reference)

        is ArrayTypeReference -> ArrayType(resolveType(reference.element))
        is FunctionTypeReference -> FunctionType(
            parameters = reference.parameters.map { resolveType(it) },
            returnType = resolveType(reference.returnType),
        )
    }

    /**
     * Resolves `T?` to [NullableType] wrapping whatever `T` resolves to.
     *
     * The parser already refuses a second `?` on one type, so the only way a [NullableType] can turn
     * up here is by the inner type *being* a nullable one — which happens when a name is reused:
     * `type Maybe = string?` is not a thing Neko has, but a class named for a built-in, or a second
     * `?` written after a suffix the parser read as `[]`, could reach this point. Reporting it here
     * rather than in the parser is what lets the message name the resolved type instead of the text.
     */
    private fun resolveNullableType(reference: NullableTypeReference): NekoType {
        val inner = resolveType(reference.inner)
        if (inner is NullableType) {
            diagnostics.error(
                "${inner.displayName} is already nullable, so the second '?' adds nothing; write " +
                    "'${inner.displayName}'",
                reference.questionSpan,
            )
            return inner
        }
        return NullableType(inner)
    }

    /**
     * Resolves a class name, which may be declared in this file or live outside it.
     *
     * A class the program declares — its own, or the standard library's — resolves to that
     * declaration's real JVM name. That is the whole reason the standard library needs no `import`:
     * it is written in Neko and compiled as part of the program, so `EarlyEmitException` and
     * `EmitExtractorN` are declarations like any other rather than names looked up somewhere.
     *
     * A name the program does not declare is a class the compiler cannot see, and the file's
     * `import` clauses decide which package it belongs to: its own `locate` package by default, or
     * the imported package when there is one. This is what an `import` is for — reaching a class
     * compiled elsewhere, whose body the compiler never reads. A name that could not be a class —
     * one beginning with a lowercase letter, since Neko class names are capitalized — is reported
     * instead, so a typo such as `strng` is still caught rather than silently becoming a reference
     * to a missing class.
     */
    private fun resolveClassType(name: String, span: Span): NekoType {
        declaredClass(name, span)?.let { return ClassType(it.jvmInternalName, name) }

        // A *qualified* name — `java.util.HashMap` — says where the class lives, so the imports are
        // not consulted at all. This is the escape hatch the rest of this function describes the
        // absence of: without it a class in a package the file did not import, or one whose simple
        // name two packages share, could not be named at all.
        //
        // The platform is still the authority on whether the class exists, which keeps a typo from
        // becoming a reference to `java/util/Hashmap`. A qualified name whose package the platform
        // does not describe is taken on the file's word, exactly as an unqualified name outside
        // `java.lang` is: a class compiled elsewhere cannot be checked, and refusing it would make
        // qualified names useless for the case they exist for.
        if ('.' in name) {
            val simpleName = name.substringAfterLast('.')
            if (simpleName.firstOrNull()?.isUpperCase() != true) {
                diagnostics.error(
                    "'$name' does not end in a class name: a qualified type is written " +
                        "'package.ClassName', and the class name starts with a capital letter",
                    span,
                    unresolved = true,
                )
                return ErrorType
            }
            // The same query the expression path asks, so a qualified name means one thing whether it
            // appears as a type or as a callee. Answering `null` here is a reportable mistake, because
            // a type position cannot be anything but a type.
            qualifiedClassOrNull(name)?.let { return it }
            val packageName = name.substringBeforeLast('.')
            diagnostics.error(
                if (PlatformLibrary.hasPackage(packageName)) {
                    "'$packageName' has no class named '$simpleName'"
                } else {
                    "unknown type '$name': the JDK has no class by that name, and no package " +
                        "'$packageName' to take it from either"
                },
                span,
                unresolved = true,
            )
            return ErrorType
        }

        // A class the program does not declare but the JVM provides — `RuntimeException`,
        // `ArithmeticException`, or a class from an imported package — is one a program has to be able
        // to name in order to extend, catch or construct it. Whether it exists is answered by the
        // platform rather than by a list, so every JDK class works, including ones added later.
        //
        // Every name in [BuiltinTypes.byName] is also a real JDK class — `Integer`, `Boolean`,
        // `Character`, `String` — and none of them is reached here: [resolveType] resolves them
        // before this point so that the two spellings are one type rather than a primitive and the
        // boxed class it happens to share a name with.
        platformClassType(name)?.let { return it }

        if (!name.first().isUpperCase()) {
            diagnostics.error(
                "unknown type '$name': Neko has the eight primitives 'byte', 'short', 'char', " +
                    "'int', 'long', 'float', 'double' and 'bool' (also spelled 'boolean'), whose " +
                    "Java reference names 'Byte', 'Short', 'Character', 'Integer', 'Long', 'Float', " +
                    "'Double' and 'Boolean' are the same types, plus 'string' (also spelled " +
                    "'String'), 'Object' and 'Throwable'; array types such as 'string[]', function " +
                    "types such as '[int] -> int'; and class types, whose names start with a capital " +
                    "letter",
                span,
                unresolved = true,
            )
            return ErrorType
        }

        // The program declares this name, but in a package this file cannot see. Reported here rather
        // than left to the fallback below, which would otherwise invent `<this file's package>/Name`
        // and fail at run time with `NoClassDefFoundError` — an error whose message never mentions the
        // import that was missing. The suggested clause names the class rather than its package so the
        // fix is unambiguous, and it is the exact text the IDE's quick fix writes.
        importNeededFor(name)?.let { clause ->
            diagnostics.error(
                "unknown type '$name': it is declared in another package this file does not import — " +
                    "write 'import $clause' to reach it",
                span,
                suggestedImport = clause,
                unresolved = true,
            )
            return ErrorType
        }

        // No declaration to read an internal name from, so the package has to be picked: the first
        // import if the file wrote one, and otherwise the file's own package.
        //
        // Before falling back, though, one case is worth a diagnostic rather than a class file that
        // will not load: the name is a real `java.base` class, and its package is one no `import`
        // names. That happens to everyone who writes `HashMap` and forgets the import, and without
        // this the compiler would answer `java/util/HashMap` only if the file happened to import
        // `java.util` already — otherwise the name silently becomes part of the file's own package
        // and the failure arrives at run time as `NoClassDefFoundError`.
        suggestedImportFor(name)?.let { packageName ->
            diagnostics.error(
                "unknown type '$name': '$name' is $packageName.$name, which this file does not " +
                    "import — write 'import $packageName' to reach it",
                span,
                suggestedImport = packageName,
                unresolved = true,
            )
            return ErrorType
        }

        return ClassType(importedPrefix() + name, name)
    }

    /**
     * The package a real `java.base` class named [simpleName] has to be imported from, when the file
     * does not already import one that provides it, or `null` when no import would help.
     *
     * Three conditions all have to hold, and each is a way this can be the wrong advice:
     *
     *  * the platform must know the name at all — otherwise it is an ordinary name from a package
     *    compiled elsewhere, and the file's own word is all there is to go on;
     *  * it must not be in `java.lang`, where it would already have resolved and this code would not
     *    be running;
     *  * exactly one package must declare it. A name two packages both hold (`Timer` would be one)
     *    has no single correct import, and naming one of them would be a guess presented as advice.
     *
     * The imported packages are excluded because reaching this point already means none of them
     * answered — either the class is not in them, or the package is one the platform does not
     * describe and so could not be checked.
     */
    private fun suggestedImportFor(simpleName: String): String? {
        val candidates = PlatformLibrary.packagesOf(simpleName)
        if (candidates.size != 1) return null
        val only = candidates.single()
        if (only == "java.lang") return null
        // A package the file already imports has already been searched by
        // `platformClassInImportedPackages`, so reaching here means it does not provide the class.
        // Reporting "write the import you already wrote" would be nonsense.
        if (importPrefixes[currentClass]?.contains(only.replace('.', '/') + "/") == true) return null
        return only
    }

    /**
     * The class named [simpleName] the program declares and the *current file* is allowed to name.
     *
     * `locate` is what decides that, and it decides it the way a package decides it in Java: a class
     * in the file's own package is visible by its simple name, and a class in another package is not
     * — the file has to `import` that package (or a class in it) first. Before this rule existed the
     * lookup ignored packages entirely, so a name resolved to whatever the program declared
     * anywhere, and an `import` was decoration: a file could name `Helper` from a package it had
     * never mentioned and the compiler would quietly find it. That made every class effectively
     * global and left nothing for `import` to do.
     *
     * [classes] still holds every declaration by simple name, because duplicate simple names across
     * packages remain an error and so this map can never hold two candidates. What is added here is
     * the *permission* check, not a second lookup: [isVisible] answers "may this file name that
     * class", and the answers differ per file, which is why the question is asked at resolution time
     * rather than once at declaration time.
     */
    private fun declaredClass(simpleName: String, span: Span? = null): ClassSymbol? {
        val candidates = visibleCandidates(simpleName)
        if (candidates.size > 1) reportAmbiguousSimpleName(candidates, simpleName, span)
        return candidates.firstOrNull()
    }

    /** Guards [declaredClass] against reporting one ambiguity once per expression. */
    private var lastAmbiguity: ClassSymbol? = null

    /**
     * Reports that [simpleName] names two classes this file can reach, and says which import decides.
     *
     * Only possible when two imported packages each declare the name: a class in the file's own
     * package is unique, and would have won before this is reached. The message names both
     * candidates, because choosing one for the reader would be a guess that shows up later as a
     * confusing error about members.
     *
     * Anchored at [span] — the name as written — when the caller has one, so the caret lands on the
     * ambiguous word rather than on one of the two declarations it might mean.
     */
    private fun reportAmbiguousSimpleName(
        candidates: List<ClassSymbol>,
        simpleName: String,
        span: Span?,
    ) {
        val first = candidates[0]
        val second = candidates[1]
        if (lastAmbiguity === first) return
        lastAmbiguity = first

        fun where(symbol: ClassSymbol): String =
            symbol.packagePrefix.removeSuffix("/").replace('/', '.').ifEmpty { "the unnamed package" }

        diagnostics.error(
            "'$simpleName' is ambiguous: it is declared in both '${where(first)}' and " +
                "'${where(second)}', and this file imports both; write '${importFor(first)}' or " +
                "'${importFor(second)}' to say which one is meant",
            span ?: first.span,
            notes = listOf(
                diagnostics.note("one declaration is here", first.span),
                diagnostics.note("the other is here", second.span),
            ),
        )
    }

    /**
     * The class a bare [simpleName] denotes here, or `null` when nothing reachable goes by that name.
     *
     * Two packages may each declare a `Helper`, so a simple name is a question rather than a lookup,
     * and the answer is the one this file can actually name:
     *
     *  * **The same `locate` package wins outright**, and there can only be one such class — a second
     *    declaration in one package is the same internal name, which is refused when it is declared.
     *  * **Otherwise a visible candidate answers**, where visibility is the import rule of
     *    [isVisible]. Several visible candidates means the name is ambiguous: two imported packages
     *    both hold a `Helper` and nothing in the file says which. [declaredClass] reports that; the
     *    lookup itself stays silent so a question asked from a check that is merely probing cannot
     *    produce a diagnostic.
     *
     * A candidate that is not visible at all is *not* an answer. [invisibleClass] is the separate
     * question the diagnostic asks, so that "you forgot an import" and "nothing goes by that name"
     * stay distinguishable.
     */
    private fun classBySimpleName(simpleName: String): ClassSymbol? = visibleCandidates(simpleName).firstOrNull()

    /**
     * Every declaration of [simpleName] this file may name, the same-package one first.
     *
     * More than one means the name is ambiguous, which is only possible when two *imported* packages
     * each declare it: the same-package class is unique by construction.
     */
    private fun visibleCandidates(simpleName: String): List<ClassSymbol> {
        val candidates = classesBySimpleName[simpleName] ?: return emptyList()
        val owner = currentClass
        if (owner != null) {
            candidates.firstOrNull { it.packagePrefix == owner.packagePrefix }?.let { return listOf(it) }
        }
        return candidates.filter { isVisible(it) }
    }

    /**
     * Whether the current file may name [symbol] — the same package, or an import that reaches it.
     *
     * Two things count as "reaching it":
     *
     *  * **The same `locate` package.** This is the rule the request is about: two files that declare
     *    the same `locate` are one package, so neither needs to say anything to see the other.
     *  * **An import naming that package.** `import other.pkg` brings `other.pkg` into reach. A
     *    single-class import such as `import other.pkg.Helper` contributes its *package* too, which is
     *    the rule [NekoFile.importPrefixes] already documented for platform classes: naming one class
     *    of a package says where your names come from, and [importedClasses] is what additionally
     *    *pins* a simple name so a second package cannot quietly answer for it.
     *
     * A file with no `locate` clause lives in the unnamed package, and so does every other file
     * without one — which is what makes a directory of `locate`-less files work with no imports.
     *
     * The standard library is deliberately *not* exempt. It is written in Neko and compiled into the
     * program like any other source, so a program that names `EmitYieldException` has to see it the
     * way it sees everything else. Nothing in the library is named by hand by a Neko author, though:
     * `scratch`, `nyan` and `yield` are lowered by the compiler against a symbol it resolves from
     * [classes] directly, which is why those three still work with no import at all.
     */
    private fun isVisible(symbol: ClassSymbol): Boolean {
        val owner = currentClass ?: return true
        if (symbol.packagePrefix == owner.packagePrefix) return true
        return importPrefixes[owner].orEmpty().contains(symbol.packagePrefix)
    }

    /**
     * The class [simpleName] names when it is *not* visible from here, for the diagnostic that says
     * so — or `null` when the program declares nothing by that name.
     *
     * Kept separate from [declaredClass] so the error path can still explain itself: a name that
     * resolves to a class in another package is a very different mistake from a name that resolves to
     * nothing, and telling the author which one they made is the difference between "you forgot an
     * import" and "you misspelled it".
     */
    private fun invisibleClass(simpleName: String): ClassSymbol? =
        classesBySimpleName[simpleName]?.firstOrNull { !isVisible(it) }

    /**
     * The `import` clause that would make [symbol] reachable, e.g. `other.pkg.Helper`.
     *
     * The class itself is named rather than its package, because that is the narrower statement: it
     * says which class is meant and cannot accidentally admit a second one. The IDE's quick fix
     * writes exactly this, so the diagnostic and the fix agree on the text.
     */
    private fun importFor(symbol: ClassSymbol): String {
        val packageName = symbol.packagePrefix.removeSuffix("/").replace('/', '.')
        return if (packageName.isEmpty()) symbol.name else "$packageName.${symbol.name}"
    }

    /** The `import` text that would make [simpleName] reachable, or `null` when nothing declares it. */
    private fun importNeededFor(simpleName: String): String? = invisibleClass(simpleName)?.let { importFor(it) }

    /**
     * The class [name] denotes when it is written on the left of a `.` — `Holder.shared`,
     * `Holder.util(1)` — or `null` when the name is an ordinary value rather than a class.
     *
     * This is the same question [declaredClass] answers, and it is routed through it deliberately.
     * There are three places that decide whether a capitalized name is a class (a static field read, a
     * static field write, and a static call), and each used to consult `classes` directly. That made
     * `Holder.shared` resolve across packages while `Holder` in a *type* position did not, so the
     * package rule could be walked around by reaching for a member instead of the class itself.
     * Funnelling them through one function is what keeps the two paths saying the same thing.
     */
    private fun classNamed(name: String, span: Span? = null): ClassSymbol? = declaredClass(name, span)



    /**
     * `java/[package]/[simpleName]` for a class the file's imports make reachable, or `null`.
     *
     * A package the platform describes is answered from [PlatformLibrary], so the class has to be in
     * it — which is what stops `import java.util` from making every typo resolve. A package outside
     * that index — a third-party library, or a module other than `java.base` — cannot be enumerated,
     * so the candidate is loaded by its full name instead: that is the same authority the JVM uses,
     * and it is what lets `import org.example` reach a class that really exists while still refusing
     * a name that does not.
     */
    private fun platformClassInImportedPackages(simpleName: String): String? {
        val owner = currentClass ?: return null

        // A single-class import answers first and answers *only*: when a file wrote
        // `import java.util.HashMap` it has said which `HashMap` it means, so no other imported
        // package gets to supply one. That restriction is the whole difference between this form and
        // the package form — without it the clause would be a longer way to write `import java.util`.
        val pinned = importedClasses[owner]?.get(simpleName)
        if (pinned != null && pinned.simpleName == simpleName) {
            return pinned.name.replace('.', '/')
        }

        for (prefix in importPrefixes[owner].orEmpty()) {
            // `importPrefixes` stores JVM form (`java/util/`), which is what a class file needs.
            val packageName = prefix.removeSuffix("/").replace('/', '.')
            if (PlatformLibrary.hasPackage(packageName)) {
                if (simpleName in PlatformLibrary.classesIn(packageName)) return prefix + simpleName
            } else {
                val candidate = "$packageName.$simpleName"
                if (loadQualifiedClass(candidate) != null) return prefix + simpleName
            }
        }
        return null
    }

    /**
     * Resolves an unqualified capitalized name as a platform class (`java.lang.*`, or a class from
     * an imported package), or `null` when neither applies.
     */
    private fun platformClassType(className: String?): ClassType? {
        if (className == null || className.isEmpty() || !className.first().isUpperCase()) return null
        if (isPlatformClass(className)) return ClassType("java/lang/$className", className)
        val imported = platformClassInImportedPackages(className)
        if (imported != null) return ClassType(imported, className)
        return null
    }

    /**
     * The package prefix an undeclared class name belongs to: the current file's first `import`, or
     * its own `locate` package when it wrote none.
     *
     * This is the fallback for a name no imported *platform* package provided, so it is what keeps a
     * program able to name a class compiled elsewhere: such a package cannot be inspected, and the
     * file's first import is the package it said its names come from.
     */
    private fun importedPrefix(): String {
        val owner = currentClass ?: return packagePrefix()
        return importPrefixes[owner]?.firstOrNull() ?: packagePrefix()
    }

    /**
     * Whether `java.lang.[name]` is a class this JVM can load.
     *
     * Asking the platform keeps the rule the same one `javac` uses — an unqualified capitalized name
     * is looked up in `java.lang` first — without the compiler having to carry a table of the JDK's
     * classes. A name that is not there falls through to the file's own package, which is what lets a
     * program name a class it does not declare.
     */
    private fun isPlatformClass(name: String): Boolean = loadPlatformClass(name) != null

    private fun pushScope(identifier: Any?) {
        scopes.addLast(Scope(identifier))
    }

    private fun popScope() {
        scopes.removeLast()
    }

    private class Scope(val identifier: Any?) {
        val symbols: LinkedHashMap<String, Symbol> = LinkedHashMap()
    }

    /** Identity holder for one lambda literal, used to decide what a reference must capture. */
    private class LambdaState(val identity: Any) {
        /** The type produced by `yield`, set by the first `yield` analysed. */
        var expectedReturnType: NekoType? = null

        /**
         * Whether [expectedReturnType] came from the function type the lambda is used as rather than
         * from an earlier `yield`.
         *
         * The two produce the same *check* but want different *words*: a mismatch against the
         * destination is not a disagreement with a previous `yield`, and saying it was would send the
         * reader looking for a `yield` that is not there.
         */
        var returnTypeIsDeclared: Boolean = false
    }

    private companion object {
        /**
         * How many values one `emit` clause may name.
         *
         * The standard library provides `EmitExtractor0` through `EmitExtractor9`, so nine is the
         * largest arity a `try` extractor can be compiled against.
         */
        const val MAX_EMIT_VALUES = 9

        /**
         * The name a clause writes to say its handlers answer nothing: `emit [int] -> void`.
         *
         * It is a result-only spelling. Nowhere else can `void` name a type, because a local, a
         * parameter or a field of type `void` has no value to hold —so this name is deliberately kept
         * out of [BuiltinTypes.byName] and intercepted only by [resolveEmitResultType].
         */
        const val VOID_TYPE_NAME = "void"

        /**
         * The interface a `for ... in` walks, by JVM internal name.
         *
         * `java.lang.Iterable` rather than a Neko interface of our own: the protocol the loop needs is
         * exactly Java's, so implementing it is what lets a Neko class be handed to Java code that
         * expects an `Iterable` — and what lets a Neko program walk a `java.util.List` it obtained from
         * the platform. A private copy would satisfy the loop and nothing else.
         *
         * It needs no `import` because it is in `java.lang`, which every file can name. [ITERATOR] is
         * not, so a program that writes the `iterator()` signature itself has to import `java.util`;
         * the loop does not, since the compiler emits that call.
         *
         * An array needs nothing like it — the JVM has no array interface to implement, so the
         * compiler emits `length`/index reads inline for that case and this interface only ever
         * appears for a class that declares it.
         */
        const val ITERABLE_INTERFACE = "java/lang/Iterable"

        /**
         * The interface `iterator()` answers, by JVM internal name.
         *
         * Named separately because it is what the code generator emits the `hasNext`/`next` calls
         * against, and because the two live in different packages: a source file that writes
         * `fun iterator(): Iterator` needs `import java.util` for the latter but not the former.
         */
        const val ITERATOR_INTERFACE = "java/util/Iterator"

        /**
         * `java.lang.Class`, as the type `value::generic` answers.
         *
         * The elements of the array are `Class` because that is the one type that names a class at
         * run time, and the JVM already carries it: no class file of the program's own is needed to
         * hold a token.
         */
        val CLASS_TYPE: NekoType = ClassType("java/lang/Class", "Class")

        /**
         * Simple name of the exception a `yield` throws, declared in the compiler's own standard library.
         *
         * Looked up among the program's classes, because the compiler compiles the library into every
         * program; see [checkStandardLibrary]. Its package is the library's, so no constant names it.
         */
        const val EMIT_YIELD_EXCEPTION_SIMPLE_NAME = "EmitYieldException"

        /**
         * The methods `java.lang.Enum` declares `final`, which an enum therefore cannot override.
         *
         * The JVM refuses to load a class that overrides a final method, so a `miao class` declaring
         * one of these would compile into a `VerifyError` at run time. `toString` is deliberately not
         * in the list: `Enum` leaves it open, and an enum that overrides it is legal.
         */
        val ENUM_FINAL_METHODS = setOf("name", "ordinal", "equals", "hashCode", "compareTo")
    }
}

// --------------------------------------------------------------------- helpers

/** Whether a symbol is a declaration a new local declaration could shadow. */
private fun Symbol.isDeclaration(): Boolean = this is LocalSymbol || this is FieldSymbol

/**
 * Whether [this] is one of the four operators whose result is a `bool` rather than an operand.
 *
 * Arithmetic and comparison share their operand rule — same numeric type on both sides — and differ
 * only in what they are worth, so which one this is *is* the answer to "what is the result type".
 */
private val BinaryOperator.isRelational: Boolean
    get() = this == BinaryOperator.LESS || this == BinaryOperator.LESS_EQUAL ||
        this == BinaryOperator.GREATER || this == BinaryOperator.GREATER_EQUAL

/**
 * Whether a symbol may be the target of an assignment.
 *
 * Only locals and fields can be, and only when they were not declared with `val`.
 */
private val Symbol.isAssignableDeclaration: Boolean
    get() = when (this) {
        is LocalSymbol -> isMutable
        is FieldSymbol -> isMutable
        else -> false
    }

/** The noun used when describing what kind of thing a symbol is. */
private fun Symbol.describeKind(): String = when (this) {
    is FieldSymbol -> "field"
    is FunctionSymbol -> "function"
    is ClassSymbol -> "class"
    is LocalSymbol -> if (isParameter) "parameter" else "variable"
}

private val NekoType.isVoidOrError: Boolean get() = this is VoidType || isError

/**
 * The numeric types, i.e. the ones arithmetic and ordering are defined on.
 *
 * `bool` is not among them even though it is a primitive: `true + true` has no answer the language
 * wants, and treating a boolean as a number is how a program ends up comparing one against an `int`.
 */
private val NekoType.isNumeric: Boolean get() = BuiltinTypes.isNumeric(this)

/**
 * The inclusive range of an integral type, or `null` for one that has none.
 *
 * `char` is unsigned, so its range starts at zero: that is what makes `x: char = -1` a mistake worth
 * reporting rather than a value that quietly wraps to 65535.
 */
private fun NekoType.integralRange(): LongRange? = BuiltinTypes.integralRangeOf(this)

/**
 * Types with a well-defined zero value, which may therefore be declared without an initializer.
 *
 * Every primitive defaults to `0` or `false` in its own type — a `long` field starts at `0L`, not at
 * an `int` zero that would need converting — and every reference type defaults to `null`, exactly as
 * the JVM's field initialization does. That is what lets the standard library declare
 * `private val data: Object[]` and assign it in the constructor.
 */
private val NekoType.hasImplicitDefault: Boolean
    get() = isNumeric || this == BoolType || isReferenceType || isError

/**
 * A conservative "does this body definitely return on every path" test.
 *
 * Used only to reject a non-void function that can plainly fall off its end. It deliberately errs
 * towards accepting, so a valid program is never rejected.
 */
private fun BlockStatement.alwaysReturns(): Boolean {
    if (statements.isEmpty()) return false
    if (statements.any { it.alwaysReturns() }) return true
    return statements.last().alwaysReturns()
}

private fun Statement.alwaysReturns(): Boolean = when (this) {
    is ReturnStatement -> true
    is BlockStatement -> alwaysReturns()
    is IfStatement -> elseBranch != null && thenBranch.alwaysReturns() && elseBranch.alwaysReturns()
    is WhenStatement -> hasElseBranch && branches.all { it.body.alwaysReturns() }
    is WhileStatement -> false
    // A `for` loop leaves through its condition, its body or its update. With no condition there is no
    // test to fail, so the only way out is a transfer inside the body — and since the body repeats,
    // one that *can* return on some pass is enough: the next pass offers the same chance. That is why
    // `for (;;) { if (c) { return 1 } }` counts, even though the `if` alone would not.
    is ForStatement ->
        condition == null && (body.containsReturn() || (update?.containsReturn() == true))
    // A `for ... in` over an array or an iterable may always have zero elements, so it falls through.
    is ForEachStatement -> false
    // A `purr` block always runs its body, so it can only fall through if the body can. (A `try` is
    // different: its handler may not run at all.)
    is PurrStatement -> body.alwaysReturns()
    // A `try` returns only if every path out of it does, which is more than a conservative test can
    // tell; reporting "maybe falls through" is the safe answer.
    // `scratch` is not terminating: the handler it calls answers a value, and the site continues.
    is YieldStatement, is NyanStatement, is VariableDeclarationStatement, is AssignmentStatement,
    is ExpressionStatement, is TryStatement, is TryScratchStatement, is IndexAssignmentStatement,
    is IncrementStatement, is PreGroupDeclaration, is BlepDeclaration,
    -> false
}

/**
 * Whether any path through this statement transfers control out of the enclosing function.
 *
 * Weaker than [alwaysReturns]: this answers "can", not "must". It exists for an endless `for` loop,
 * where a `return` reachable on any pass is enough to stop the loop falling through — the next pass
 * repeats the same test, so a chance that does not depend on the loop variable is a chance that comes.
 */
private fun Statement.containsReturn(): Boolean = when (this) {
    is ReturnStatement -> true
    is BlockStatement -> statements.any { it.containsReturn() }
    is IfStatement -> thenBranch.containsReturn() || elseBranch?.containsReturn() == true
    is WhenStatement -> branches.any { it.body.containsReturn() }
    is WhileStatement -> body.containsReturn()
    is ForStatement -> body.containsReturn() || update?.containsReturn() == true
    is ForEachStatement -> body.containsReturn()
    is PurrStatement -> body.containsReturn()
    is YieldStatement, is NyanStatement, is VariableDeclarationStatement, is AssignmentStatement,
    is ExpressionStatement, is TryStatement, is TryScratchStatement, is IndexAssignmentStatement,
    is IncrementStatement, is PreGroupDeclaration, is BlepDeclaration,
    -> false
}

/**
 * Whether execution can never reach the statement after this one.
 *
 * Narrower than [alwaysReturns]: an `if` without an `else` still lets control through, so only the
 * statements that unconditionally transfer control count here.
 */
private fun Statement.terminatesFlow(): Boolean = when (this) {
    // A `nyan` leaves the enclosing `try` handler and never falls through to the next statement of
    // that handler's block; a `return` leaves the function. A `yield` leaves by throwing, so it does
    // not fall through either — whether it unwinds to a `try` or returns from a lambda, the statement
    // after it in the same block is never reached.
    is ReturnStatement, is NyanStatement, is YieldStatement -> true
    is BlockStatement -> statements.any { it.terminatesFlow() }
    // A `purr` block runs unconditionally, so a `return` or `nyan` inside it always terminates the
    // method — the unlock the compiler inserts does not change where control ends up.
    is PurrStatement -> body.terminatesFlow()
    is IfStatement -> elseBranch != null && thenBranch.terminatesFlow() && elseBranch.terminatesFlow()
    is WhenStatement -> hasElseBranch && branches.all { it.body.terminatesFlow() }
    else -> false
}

/** The words used when reporting a statement whose successors are unreachable. */
private fun Statement.describeTerminator(): String = when (this) {
    is ReturnStatement -> "'return'"
    is NyanStatement -> "'nyan'"
    is YieldStatement -> "'yield'"
    is BlockStatement -> "block"
    is IfStatement -> "'if'"
    is WhenStatement -> "'when'"
    is PurrStatement -> "'purr'"
    else -> "statement"
}

/**
 * Whether this statement can reach a `try` handler, directly or through something it calls.
 *
 * An `emit` clause is satisfied by any of three things, and they are not the same:
 *
 *  * A `scratch`, which enters a handler and takes its answer.
 *  * A `yield`, which leaves through the `try` that declared the handler.
 *  * A call to another function with the same clause, which passes the ability on unchanged.
 *
 * Used to reject a function that declares an `emit` clause but has no way at all to reach a handler:
 * such a function can only ever return normally, so the clause describes a departure that cannot
 * happen.
 */
private fun Statement.reachesHandler(): Boolean = when (this) {
    is BlockStatement -> statements.any { it.reachesHandler() }
    is IfStatement -> thenBranch.reachesHandler() || elseBranch?.reachesHandler() == true
    is WhenStatement -> branches.any { it.body.reachesHandler() }
    is WhileStatement -> body.reachesHandler()
    is ForStatement -> body.reachesHandler() || update?.reachesHandler() == true
    is ForEachStatement -> body.reachesHandler()
    // A `scratch` inside a `try`'s handler belongs to that handler's own function, but a `yield`
    // there answers *this* `try` — which is the one case that makes the enclosing function's clause
    // reachable from inside the block.
    is TryStatement -> extractorBody.statements.any { it.reachesHandler() }
    // `try f() scratch ...` reaches a handler through the `scratch` it carries, which is the whole
    // point of the form: the failure is answered by one declared elsewhere.
    is TryScratchStatement -> scratch.reachesHandler() || call.reachesHandler()
    is PurrStatement -> body.statements.any { it.reachesHandler() }
    is ExpressionStatement -> expression.reachesHandler()
    is VariableDeclarationStatement -> initializer?.reachesHandler() == true
    is ReturnStatement -> value?.reachesHandler() == true
    is AssignmentStatement -> value.reachesHandler()
    is IndexAssignmentStatement -> value.reachesHandler()
    // A `yield` written directly in the body leaves through an enclosing `try`, so the function does
    // reach a handler — the caller's.
    is YieldStatement -> true
    else -> false
}

/** Whether this expression reaches a `try` handler. See [Statement.reachesHandler]. */
private fun Expression.reachesHandler(): Boolean = when (this) {
    is ScratchExpression -> true
    is NyaExpression ->
        failureBlock?.statements?.any { it.reachesHandler() } == true ||
            elseBlock?.statements?.any { it.reachesHandler() } == true
    is BinaryExpression -> left.reachesHandler() || right.reachesHandler()
    is UnaryExpression -> operand.reachesHandler()
    is ChainedComparisonExpression -> operands.any { it.reachesHandler() }
    is CallExpression -> callee.reachesHandler() || arguments.any { it.reachesHandler() }
    is IndexExpression -> array.reachesHandler() || index.reachesHandler()
    is MemberAccessExpression -> receiver.reachesHandler()
    is TryScratchExpression -> scratch.reachesHandler() || call.reachesHandler()
    else -> false
}

/**
 * Whether evaluating this expression can throw an `AssertionError` before it answers anything.
 *
 * This is what decides whether a `nya`'s failure block may believe the false reading's facts. The
 * block is entered by a false condition *and* by a throw out of the condition, so a fact is only
 * sound there if the throw cannot happen — see `Analyzer.failureFacts`. The walk therefore has to
 * find every way an `AssertionError` can be raised, which in this language means every bare `nya`
 * that might run, including ones inside functions the expression calls.
 *
 * Two answers are deliberately conservative:
 *
 *  * **A call may throw.** The bare `nya` might be inside the callee, or inside something the callee
 *    called; following that would mean analysing the whole program before this one expression. Since
 *    the cost of a wrong "no" is a null dereference the analyzer promised to prevent, the cheap answer
 *    is the safe one.
 *  * **An inner `nya` may throw.** Either it is bare, and so throws by construction, or it has blocks
 *    of its own, which are places a bare `nya` can be written. Chasing that through the statement
 *    tree would buy very little for a question whose honest answer is already "it may".
 *
 * Everything else is settled by its operands: arithmetic, comparisons and `!` check nothing
 * themselves, but `h == null || load()` is a throw away from being false. A lambda is a value being
 * built rather than a body being run, so its own `nya`s are not this expression's problem.
 */
private fun canThrowAssertion(expression: Expression): Boolean = when (expression) {
    is CallExpression, is NyaExpression, is TryExpression, is TryScratchExpression -> true

    // `scratch` calls the handler method. Plainly written it can only reach a handler that was found
    // — an unmatched site is an error — so the only assertion it can raise is one inside the values
    // it passes.
    is ScratchExpression -> expression.values.any { canThrowAssertion(it) }

    is BinaryExpression -> canThrowAssertion(expression.left) || canThrowAssertion(expression.right)
    is UnaryExpression -> canThrowAssertion(expression.operand)
    is ChainedComparisonExpression -> expression.operands.any { canThrowAssertion(it) }

    // Neither reading a member nor indexing an array checks anything, but the receiver and the index
    // are expressions in their own right and may call. `::generic` is one more field read.
    is MemberAccessExpression -> canThrowAssertion(expression.receiver)
    is GenericAccessExpression -> canThrowAssertion(expression.receiver)
    is IndexExpression -> canThrowAssertion(expression.array) || canThrowAssertion(expression.index)

    // Values already in hand, and a value being built.
    is IntegerLiteral,
    is DoubleLiteral,
    is CharLiteral,
    is StringLiteral,
    is BoolLiteral,
    is NullLiteral,
    is NameExpression,
    is ThisExpression,
    is LambdaExpression,
    -> false
}
