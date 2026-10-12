package com.github.kusa233.neko.analyzer

import com.github.kusa233.neko.ast.*
import com.github.kusa233.neko.diagnostics.DiagnosticBag
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
    private val classes = LinkedHashMap<String, ClassSymbol>()

    /**
     * The packages each class may reach by simple name, from the `import` clauses of its file.
     *
     * A name is resolved against this file's own package by default; an `import` is what adds
     * another package a name may come from. The standard library appears in no such list: it is
     * compiled as part of the program, so its classes are already declared and resolve from
     * anywhere without an import.
     */
    private val importPrefixes = LinkedHashMap<ClassSymbol, List<String>>()

    /** Non-null while analysing the body of a lambda literal. */
    private var currentLambda: LambdaState? = null

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
    private var currentExtractor: ExtractorTry? = null

    /**
     * `true` while the callee of a `try … { … -> }` is being resolved.
     *
     * The call a `try` wraps is the one call to a raising function that is legal, so the check that
     * rejects such a call everywhere else has to know it is looking at that one.
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
     * Whether the "is the standard library present?" diagnostic has already been considered.
     *
     * It is a property of the program rather than of any one `raise`, so it is reported once however
     * many times `raise` appears: repeating it on every `raise` in a large file would bury the rest of
     * the output.
     */
    private var standardLibraryChecked = false

    /** Parameter symbols, keyed by declaration, so code generation and analysis agree on order. */
    private val parametersByFunction = LinkedHashMap<FunctionDeclaration, List<LocalSymbol>>()

    /**
     * Whether the function currently being checked forwards a `raise` to its own caller.
     *
     * Set when a call passes the `emit` clause on unchanged, which is a second way a function with an
     * `emit` clause can reach its caller's extractor — beside writing `raise` itself. The
     * "declares an 'emit' clause but can never 'raise'" check consults it.
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

    /** Parameter symbols for [function]; used by the code generator to place locals. */
    fun parametersOf(function: FunctionDeclaration): List<LocalSymbol> =
        parametersByFunction[function].orEmpty()

    /** All parameter symbols, for the code generator. */
    fun allParameters(): Map<FunctionDeclaration, List<LocalSymbol>> = parametersByFunction

    // -------------------------------------------------------------- declaration

    private fun declareClasses() {
        for (declaration in program.classes) {
            val previous = classes[declaration.name]
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
            val packagePrefix = program.packagePrefixOf(declaration)
            val symbol = ClassSymbol(
                name = declaration.name,
                span = declaration.nameSpan,
                jvmInternalName = packagePrefix + declaration.name,
                packagePrefix = packagePrefix,
                isInterface = declaration.isInterface,
                typeParameters = declaration.typeParameters,
            )
            classes[declaration.name] = symbol
            // Resolution reads the declaring file's clauses rather than a per-file flag, because a
            // class' own `import` clauses are what decide where *its* bodies may look for a name.
            importPrefixes[symbol] = program.fileOf(declaration)?.importPrefixes().orEmpty()
        }
    }

    private fun resolveSignatures() {
        for (declaration in program.classes) {
            val classSymbol = classes[declaration.name] ?: continue
            declaration.symbol = classSymbol
            currentClass = classSymbol
            resolveSupertypes(declaration, classSymbol)
            currentClass = null
        }
        // Local variable scopes for type parameters need the supertypes resolved first, so a
        // supertype's own type parameters are known before a member mentions one.
        for (declaration in program.classes) {
            val classSymbol = classes[declaration.name] ?: continue
            currentClass = classSymbol
            declareFields(declaration, classSymbol)
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
     * hierarchy and what `catch` consults to decide whether a type is throwable.
     */
    private fun resolveSupertypes(declaration: ClassDeclaration, classSymbol: ClassSymbol) {
        val superclass = declaration.superclass
        if (superclass != null) {
            val type = resolveType(
                NamedTypeReference(superclass.name, superclass.nameSpan, superclass.span),
            )
            superclass.resolvedType = type
            if (type is ClassType) {
                classSymbol.superclass = type
                val parent = classes[type.simpleName]
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
                NamedTypeReference(implemented.name, implemented.nameSpan, implemented.span),
            )
            implemented.resolvedType = type
            if (type is ClassType) {
                classSymbol.interfaces += type
                classes[type.simpleName]?.let { classSymbol.interfaceSymbols += it }
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
            val classSymbol = classes[declaration.name] ?: continue
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

    private fun declareFunctions(declaration: ClassDeclaration, classSymbol: ClassSymbol) {
        for (function in declaration.functions) {
            val parameterTypes = function.parameters.map { resolveType(it.declaredType) }
            val returnType = function.returnType?.let { resolveType(it) } ?: VoidType
            function.resolvedParameterTypes = parameterTypes
            function.resolvedReturnType = returnType
            function.resolvedEmitTypes = function.emitTypes.map { resolveType(it) }

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
                        "function with no body has no 'raise' to hand values back with",
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
                hasEmitClause = function.hasEmitClause,
                isAbstract = function.isAbstract,
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
     * A function that can `raise` has *two* ways to leave, and they are not alternatives: it may
     * `raise`, which sends values to the caller's extractor and skips the rest of the body, or it may
     * `return` normally. Declaring a return type as well is therefore meaningful — it is the value
     * the call produces whenever the function does not raise, which is what `r = try f() { ... }`
     * reads. Only the arity of the clause is checked here.
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

    // ------------------------------------------------------------------ bodies

    private fun checkBodies() {
        for (declaration in program.classes) {
            val classSymbol = classes[declaration.name] ?: continue
            currentClass = classSymbol
            checkInterfacesImplemented(declaration, classSymbol)
            for (field in declaration.fields) checkFieldInitializer(field)
            for (function in declaration.functions) checkFunctionBody(function, classSymbol)
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
        val valueType = analyzeExpression(initializer)
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

        // A function that can `raise` is exempt from the "must return on every path" rule: a `raise`
        // is a legitimate way to leave it, and the extractor — not a value — is what the caller
        // receives. Requiring a return as well would make every such function untestable to write.
        if (!declaredReturn.isVoidOrError && !symbol.canRaise && !body.alwaysReturns()) {
            diagnostics.error(
                "function '${function.name}' declares that it returns ${declaredReturn.displayName} " +
                    "but its body can finish without executing a 'return' statement",
                body.span,
            )
        }

        if (symbol.canRaise && !forwardsRaise && body.statements.none { it.containsRaise() }) {
            diagnostics.error(
                "function '${function.name}' declares an 'emit' clause but can never 'raise', so " +
                    "its results cannot be received",
                function.nameSpan,
            )
        }

        popScope()
        popScope()
        currentFunction = null
        implicitThisClass = null
    }

    // -------------------------------------------------------------- statements

    /** Analyses a block in a fresh scope. */
    private fun analyzeBlock(block: BlockStatement) {
        pushScope(identifier = block)
        var terminated: Statement? = null
        for (statement in block.statements) {
            if (terminated != null) {
                // The sample marks the line after a `raise` as "Unreachable, notice as error level",
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
        }
        popScope()
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
            is WhenStatement -> analyzeWhen(statement)
            is RaiseStatement -> analyzeRaise(statement)
            is TryStatement -> analyzeTry(statement)
            is TryCatchStatement -> analyzeTryCatch(statement)
            is IndexAssignmentStatement -> analyzeIndexAssignment(statement)
        }
    }

    /** `array[index] = value` */
    private fun analyzeIndexAssignment(statement: IndexAssignmentStatement) {
        val arrayType = analyzeExpression(statement.array)
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
     * Reports a use of `raise` in a program that does not include the standard library.
     *
     * `raise` is not a JVM operation: it lowers to throwing the library's `EarlyEmitException` and to
     * the caller implementing the library's `EmitExtractorN`. Compiled without those sources the
     * class files would name classes that do not exist, and the failure would appear only when the
     * program was loaded. Reporting it here says what is missing while the user can still fix it.
     *
     * The `EmitExtractor` interfaces are checked too, because a `try` extractor implements one of
     * them, so a program that raises but never catches still needs them emitted.
     */
    private fun checkStandardLibrary(span: Span) {
        if (standardLibraryChecked) return
        standardLibraryChecked = true

        val missing = STANDARD_LIBRARY_TYPES.filter { classes[it] == null }
        if (missing.isEmpty()) return

        diagnostics.error(
            "'raise' needs the Neko standard library, but this program does not include it: " +
                "${missing.joinToString(", ")} " +
                "${if (missing.size == 1) "is" else "are"} not declared. Write them yourself, or " +
                "compile the library's sources together with this program — the build passes " +
                "every directory in 'neko.libraryDirectories' to the compiler alongside the program",
            span,
        )
    }

    /**
     * `raise 1 * multiplier, multiplier`
     *
     * Only meaningful inside a function that declared an `emit` clause, and the values must match
     * that clause one for one. The sample marks the line after a `raise` as unreachable — and it is,
     * because the lowering throws — so this is a terminating statement.
     */
    private fun analyzeRaise(statement: RaiseStatement) {
        val function = currentFunction
        checkStandardLibrary(statement.keywordSpan)

        if (function == null || !function.canRaise) {
            for (value in statement.values) analyzeExpression(value)
            diagnostics.error(
                "'raise' is only allowed inside a function that declares an 'emit' clause",
                statement.keywordSpan,
            )
            return
        }

        val expected = function.emitTypes
        if (statement.values.size != expected.size) {
            for (value in statement.values) analyzeExpression(value)
            diagnostics.error(
                "'raise' must provide ${expected.size} value(s) to match the 'emit' clause of " +
                    "'${function.name}' but ${statement.values.size} " +
                    "${if (statement.values.size == 1) "was" else "were"} provided",
                statement.keywordSpan,
                notes = listOf(
                    diagnostics.note("'${function.name}' declares its 'emit' clause here", function.span),
                ),
            )
            return
        }

        for ((index, value) in statement.values.withIndex()) {
            val actual = analyzeExpression(value, expected[index])
            if (!isAssignable(actual, expected[index])) {
                diagnostics.error(
                    "value ${index + 1} of 'raise' is declared as ${expected[index].displayName} by " +
                        "the 'emit' clause but has type ${actual.displayName}",
                    value.span,
                )
            }
        }
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
     * `r: int = try test(10) { a, b -> yield 1 }`
     *
     * The expression form catches exactly what the statement form does. Its own value is what the
     * call returned when it returned normally, and what the extractor's `yield` produced when it
     * `raise`d instead — so the two paths must agree on one type, and the extractor must actually
     * produce one.
     */
    private fun analyzeTryExpression(expression: TryExpression): NekoType {
        val callee = analyzeExtractorTry(expression)
        val normalResult = callee?.returnType ?: ErrorType

        val yielded = expression.yieldedType
        if (yielded == null) {
            // The value would have to come from somewhere on the `raise` path, and the extractor
            // never produces one — so a `try` used as a value would silently answer nothing.
            diagnostics.error(
                "this 'try' is used as a value, so its extractor must 'yield' the value to use " +
                    "when the call 'raise's instead of returning",
                expression.extractorBody.span,
            )
            return ErrorType
        }

        if (!isAssignable(yielded, normalResult)) {
            diagnostics.error(
                "this 'try' is worth ${normalResult.displayName} when the call returns, but its " +
                    "extractor 'yield's ${yielded.displayName} when the call 'raise's",
                expression.extractorBody.span,
            )
        }
        return normalResult
    }

    /**
     * The part both `try` forms share: check the callee may `raise`, then analyze the extractor body
     * in a scope binding the values it named.
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
        // The call is resolved here rather than through `analyzeExpression`, so its type has to be
        // recorded by hand: code generation reads it to know what the call returns on the path where
        // it returns normally, which is where a `try` expression's value comes from.
        call.resolvedType = symbol?.returnType ?: ErrorType
        for (argument in call.arguments) analyzeExpression(argument)

        if (symbol == null) {
            // Report the callee first — "not a function that may raise" is only actionable once the
            // reader knows the call itself is fine. Resolve the receiver too, for `obj.f(...)`.
            analyzeTryExtractor(statement, emptyList())
            return null
        }

        if (statement.extractorParameters.size > expected.size) {
            val extra = statement.extractorParameters[expected.size]
            diagnostics.error(
                "this extractor names ${statement.extractorParameters.size} value(s) but " +
                    "'${symbol.name}' raises ${expected.size}",
                extra.span,
            )
        }

        analyzeTryExtractor(statement, expected)
        return symbol
    }

    /**
     * Analyzes the extractor body in its own scope, binding the parameters it named to the types the
     * callee's `emit` clause declares.
     */
    private fun analyzeTryExtractor(statement: ExtractorTry, expected: List<NekoType>) {
        // A try extractor body is not a lambda: it runs in the enclosing function, so `this` and the
        // enclosing variables are all still in scope, and a `raise` inside it behaves as usual.
        val savedFunction = currentFunction
        val savedExtractor = currentExtractor
        pushScope(identifier = statement)
        currentExtractor = statement

        for ((index, parameter) in statement.extractorParameters.withIndex()) {
            val type = expected.getOrNull(index) ?: ErrorType
            parameter.resolvedType = type
            val symbol = LocalSymbol(
                name = parameter.name,
                type = type,
                span = parameter.nameSpan,
                kind = VariableKind.VAL,
                isParameter = true,
                // The extractor body is emitted inside the enclosing method, so a reference to one of
                // these is an ordinary local read, not a capture.
                enclosingLambda = currentLambda?.identity,
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
        popScope()
        currentFunction = savedFunction
    }

    /**
     * Rejects a call to a function that may `raise` when nothing catches it.
     *
     * A function with an `emit` clause does not return a value: it leaves through `raise`, and the
     * only thing that receives those values is a `try … { … -> }` extractor. Called anywhere else
     * the values would have nowhere to go, so the call is an error rather than a value that is
     * silently always null.
     *
     * There is one exception, and it is the reason [forwardsRaiseUnchanged] exists: a caller that
     * declares the *same* `emit` clause is not trying to receive the values, it is handing them on.
     */
    private fun checkCallableNormally(symbol: FunctionSymbol, span: Span) {
        // A `try f(...)` extractor is exactly the construct that receives a `raise`, so the call it
        // wraps is the one place this requirement does not apply.
        if (resolvingTryCallee) return
        if (!symbol.canRaise) return
        if (forwardsRaiseUnchanged(symbol)) return
        diagnostics.error(
            "'${symbol.name}' declares an 'emit' clause, so it never returns a value; call it as " +
                "'try ${symbol.name}(...) { ... -> }' to receive the values it 'raise's",
            span,
        )
    }

    /**
     * Whether a call to [callee] here may stand as an ordinary call because this function forwards
     * its `raise` to its own caller unchanged.
     *
     * A `raise` is a transfer of control to whoever can receive it. A function that declares the
     * same `emit [T, ...]` clause as the one it is calling has nothing to do with the values: they
     * are already in the form its own caller expects, so letting the exception travel one frame
     * further is exactly as correct as catching it and raising it again. Requiring a `try` there
     * would force every wrapper to unpack values only to hand them straight back.
     *
     * The clauses must match *exactly*, type for type. `emit [int]` forwarding into `emit [Object]`
     * would not be a pass-through — it would need a conversion, and a `try` is where that belongs.
     */
    private fun forwardsRaiseUnchanged(callee: FunctionSymbol): Boolean {
        val caller = currentFunction ?: return false
        if (!caller.canRaise) return false
        if (caller.emitTypes.size != callee.emitTypes.size) return false
        if (!caller.emitTypes.zip(callee.emitTypes).all { (mine, theirs) -> mine == theirs }) return false
        // Recorded so the body check knows this function does reach its caller's extractor, even
        // though it never writes `raise` itself.
        forwardsRaise = true
        return true
    }

    /**
     * Resolves the callee of a `try` statement, requiring a function that may `raise`.
     *
     * Returns the resolved symbol, or `null` after reporting why the call cannot be caught.
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
                        "'${selected.name}' does not declare an 'emit' clause, so it never 'raises' " +
                            "and this 'try' has nothing to catch",
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
                "'try' needs a call to a function that may 'raise', but there is no function named " +
                    "'${callee.name}'",
                callee.span,
            )
            return null
        }

        if (callee is MemberAccessExpression) {
            // Reuse the ordinary member-call path so a bad `try` callee reports the same reasons a
            // bad call would, then check the extra requirement that it may `raise`.
            val type = analyzeMemberCall(call, callee)
            val resolved = callee.resolvedFunction
            if (resolved == null || type.isError) return null
            if (!resolved.canRaise) {
                diagnostics.error(
                    "'${resolved.name}' does not declare an 'emit' clause, so it never 'raises' " +
                        "and this 'try' has nothing to catch",
                    callee.memberSpan,
                )
            }
            return resolved
        }

        // A function *value* has no declaration to read an `emit` clause from, so it can never be the
        // callee of a `try`. Refer to the declaration by name instead.
        analyzeExpression(callee)
        diagnostics.error(
            "'try' needs a call to a declared function, because only a declaration records which " +
                "values it 'raise's",
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
                enclosingLambda = currentLambda?.identity,
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
        // no effect on the enclosing variable. Reject it instead of miscompiling it.
        if (resolved is LocalSymbol && currentLambda != null &&
            resolved.enclosingLambda !== currentLambda!!.identity
        ) {
            diagnostics.error(
                "cannot assign to '${resolved.name}' inside a lambda: it is declared in the " +
                    "enclosing scope and lambdas capture variables by value",
                target.span,
                notes = listOf(diagnostics.note("'${resolved.name}' is declared here", resolved.span)),
            )
        }

        val valueType = analyzeExpression(statement.value, resolved.type)
        if (!isAssignable(valueType, resolved.type)) {
            diagnostics.error(
                "cannot assign a value of type ${valueType.displayName} to '${resolved.name}' of " +
                    "type ${resolved.type.displayName}",
                statement.value.span,
            )
        }
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
        val namedClass = className?.let { classes[it] }
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
        // the constructor that is building the object — which is exactly how the standard library
        // stores the array a `raise` carried. Anywhere else the write is an error.
        if (!field.isMutable && !inConstructorAssignment(field)) {
            diagnostics.error(
                "cannot assign to field '${field.name}' because it is declared with 'val'",
                target.memberSpan,
                notes = listOf(diagnostics.note("'${field.name}' is declared here", field.span)),
            )
        }

        val valueType = analyzeExpression(statement.value, field.type)
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
                    "'return' cannot be used inside a 'try' extractor; use 'yield' to produce the " +
                        "value of the 'try' expression"
                } else {
                    "'return' cannot be used inside a 'try' extractor; it runs when the call " +
                        "'raise's, so there is no function here to return from"
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
     * `yield x + y` — the value of the lambda it appears in, or of the `try` expression whose
     * extractor it is written in.
     *
     * Both produce a value the same way, and in both the first `yield` fixes the result type that
     * later ones must match. Only the home of the value differs: a lambda's `yield` returns from the
     * generated `invoke`, a `try` extractor's from the extractor class the compiler emits for it.
     */
    private fun analyzeYield(statement: YieldStatement) {
        val lambda = currentLambda
        val extractor = currentExtractor

        if (lambda == null && extractor == null) {
            diagnostics.error(
                "'yield' can only be used inside a lambda or a 'try' expression; use 'return' to " +
                    "return from a function",
                statement.keywordSpan,
            )
        }

        if (extractor != null && !extractor.acceptsYield) {
            // `try f(...) { a -> yield 1 }` in statement position is worth nothing, so a `yield`
            // there would be a value nobody could ever read.
            diagnostics.error(
                "a 'try' statement is worth nothing, so its extractor cannot 'yield'; write " +
                    "'result: T = try ...' to keep the value, or use 'return' to leave the " +
                    "enclosing function",
                statement.keywordSpan,
            )
        }

        // A `try` extractor takes precedence: `yield` inside one belongs to that extractor, not to a
        // lambda the extractor might itself be nested in.
        val expected = extractor?.yieldedType ?: lambda?.expectedReturnType
        val valueType = analyzeExpression(statement.value, expected)

        if (extractor != null && extractor.acceptsYield) {
            val alreadyYielded = extractor.yieldedType
            if (alreadyYielded == null) {
                extractor.yieldedType = valueType // The first `yield` fixes the result type.
            } else if (!isAssignable(valueType, alreadyYielded)) {
                diagnostics.error(
                    "this 'yield' produces ${valueType.displayName} but an earlier 'yield' in the " +
                        "same 'try' extractor produces ${alreadyYielded.displayName}",
                    statement.value.span,
                )
            }
        } else if (lambda != null) {
            if (expected == null) {
                lambda.expectedReturnType = valueType // The first `yield` fixes the result type.
            } else if (!isAssignable(valueType, expected)) {
                diagnostics.error(
                    "this 'yield' produces ${valueType.displayName} but an earlier 'yield' in the " +
                        "same lambda produces ${expected.displayName}",
                    statement.value.span,
                )
            }
        }
    }

    private fun analyzeIf(statement: IfStatement) {
        requireBool(analyzeExpression(statement.condition), statement.condition.span, "if")
        analyzeStatement(statement.thenBranch)
        statement.elseBranch?.let { analyzeStatement(it) }
    }

    private fun analyzeWhile(statement: WhileStatement) {
        requireBool(analyzeExpression(statement.condition), statement.condition.span, "while")
        analyzeStatement(statement.body)
    }

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
     * `try { ... } catch (e: CustomException) { ... } finally { ... }`
     *
     * This is how a program handles a throwable produced outside the language — a call into a class
     * some other JVM language compiled. Each `catch` names a type that must be throwable, and a
     * clause the JVM could never select is reported rather than left to fail at run time.
     */
    private fun analyzeTryCatch(statement: TryCatchStatement) {
        val seen = mutableListOf<NekoType>()

        analyzeBlock(statement.body)

        for (clause in statement.catches) {
            val caughtType = resolveType(clause.declaredType)
            clause.resolvedType = caughtType
            checkCatchable(caughtType, clause.declaredType.span, clause.parameterName)

            // A later clause that names a supertype of an earlier one can never run, because the
            // earlier clause already caught everything the later one would accept. That is dead
            // code the author cannot see, so it is reported.
            val unreachable = seen.firstOrNull { earlier -> isSubtype(caughtType, earlier) }
            if (unreachable != null) {
                diagnostics.error(
                    "this 'catch' clause can never run: an earlier clause already catches " +
                        "${unreachable.displayName}, which every ${caughtType.displayName} is",
                    clause.declaredType.span,
                )
            }
            seen += caughtType

            pushScope(identifier = clause)
            val local = LocalSymbol(
                name = clause.parameterName,
                type = caughtType,
                span = clause.parameterNameSpan,
                kind = VariableKind.VAL,
                isParameter = true,
            )
            clause.symbol = local
            scopes.last().symbols[clause.parameterName] = local
            analyzeBlock(clause.body)
            popScope()
        }

        if (statement.finallyBlock != null) {
            // A `finally` runs whatever happened, including when no exception was thrown, so it is
            // just a block. It is deliberately not exempt from the "must not return" question: a
            // `return` inside `finally` discards a pending exception, which the analyzer reports.
            analyzeBlock(statement.finallyBlock)
        }
    }

    /**
     * Rejects a `catch` clause naming something the JVM could never throw into it.
     *
     * The test is the class hierarchy, not the name: `CustomException` is only a legal catch type
     * because it extends `Exception`. A class the program declares says so itself; one the platform
     * provides is checked against the platform.
     */
    private fun checkCatchable(type: NekoType, span: Span, name: String) {
        if (type.isError) return
        if (type === ThrowableType) return
        if (type !is ClassType) {
            diagnostics.error(
                "a 'catch' clause must name a throwable type but '$name' has type " +
                    type.displayName,
                span,
            )
            return
        }

        // A class declared in the program must actually descend from Throwable. One from outside the
        // program is checked by loading it, so `catch (e: ArithmeticException)` is accepted because
        // the JDK says it is throwable, and a class that cannot be loaded is taken on trust rather
        // than reported — it may simply not be on the compiler's own classpath.
        val symbol = classes[type.simpleName]
        val isThrowable = if (symbol != null) {
            extendsThrowable(symbol)
        } else {
            platformIsThrowable(type.simpleName)
        }

        if (!isThrowable) {
            diagnostics.error(
                "a 'catch' clause cannot name '${type.displayName}': it is not a throwable, so no " +
                    "exception could ever be caught by it",
                span,
            )
        }
    }

    /** Whether [symbol], or something it extends, is a throwable the analyzer can see. */
    private fun extendsThrowable(symbol: ClassSymbol): Boolean {
        var current: ClassSymbol? = symbol
        val visited = mutableSetOf<ClassSymbol>()
        while (current != null && visited.add(current)) {
            val superclass = current.superclass ?: return false
            val simple = superclass.simpleName
            // A supertype declared in the program is walked; one the platform provides is asked
            // about directly, which is what makes `RuntimeException` and anything below it work.
            if (platformIsThrowable(simple)) return true
            current = current.superclassSymbol
        }
        return false
    }

    /**
     * Whether a class named [simpleName] exists and descends from `Throwable`.
     *
     * `false` covers both "the platform has no such class" and "it is not throwable"; a class the
     * compiler cannot load at all is treated as *not* throwable only when it is also absent from the
     * program, which the caller has already established.
     */
    private fun platformIsThrowable(simpleName: String): Boolean {
        val loaded = loadPlatformClass(simpleName) ?: return false
        return Throwable::class.java.isAssignableFrom(loaded)
    }

    /**
     * Loads `java.lang.[simpleName]` without running its static initializer, or `null` when it does
     * not exist.
     *
     * Initialization is deliberately skipped: the compiler must not execute code from the program
     * being compiled, and a class' identity is all that is being asked about here.
     */
    private fun loadPlatformClass(simpleName: String): Class<*>? = try {
        Class.forName("java.lang.$simpleName", false, javaClass.classLoader)
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        null
    }

    /** Whether a value of type [sub] is acceptable where [supertype] is expected. */
    private fun isSubtype(sub: NekoType, supertype: NekoType): Boolean {
        if (sub.isError || supertype.isError) return false
        if (sub == supertype) return true
        if (supertype === ThrowableType) return sub === ThrowableType
        val subSymbol = (sub as? ClassType)?.let { classes[it.simpleName] } ?: return false
        val superSymbol = (supertype as? ClassType)?.let { classes[it.simpleName] } ?: return false
        return subSymbol.isSubtypeOf(superSymbol)
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
    private fun isAssignable(actual: NekoType, expected: NekoType): Boolean =
        actual.isAssignableTo(expected) || isSubtype(actual, expected)

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
        is IntegerLiteral -> IntType
        is StringLiteral -> StringType
        is BoolLiteral -> BoolType
        is NameExpression -> resolveName(expression)?.type ?: ErrorType
        is ThisExpression -> analyzeThis(expression)
        is BinaryExpression -> analyzeBinary(expression)
        is ChainedComparisonExpression -> analyzeChainedComparison(expression)
        is UnaryExpression -> analyzeUnary(expression)
        is CallExpression -> analyzeCall(expression, expectedType)
        is LambdaExpression -> analyzeLambda(expression, expectedType)
        is MemberAccessExpression -> analyzeMemberAccess(expression)
        is IndexExpression -> analyzeIndexRead(expression)
        is TryExpression -> analyzeTryExpression(expression)
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
     * contents, which is what the standard library's extractors need in order to unpack a `raise`.
     */
    private fun analyzeIndexRead(expression: IndexExpression): NekoType {
        val arrayType = analyzeExpression(expression.array)
        val element = arrayElementType(arrayType, expression.array.span)
        analyzeIndex(expression.index, expression.indexSpan)
        return element ?: ErrorType
    }

    /**
     * `a > b > c`, which means `a > b && b > c`.
     *
     * Every operator in the chain is relational, so every operand between two operators participates
     * in two comparisons and must be an int; only the result is a bool.
     */
    private fun analyzeChainedComparison(expression: ChainedComparisonExpression): NekoType {
        val operandTypes = expression.operands.map { analyzeExpression(it) }
        var invalid = false

        for ((index, type) in operandTypes.withIndex()) {
            if (type.isError) {
                invalid = true
                continue
            }
            if (type != IntType) {
                invalid = true
                diagnostics.error(
                    "a chained comparison compares int values but operand ${index + 1} here has " +
                        "type ${type.displayName}",
                    expression.operands[index].span,
                )
            }
        }

        return if (invalid) ErrorType else BoolType
    }

    private fun analyzeUnary(expression: UnaryExpression): NekoType {
        val operand = analyzeExpression(expression.operand)
        return when (expression.operator) {
            UnaryOperator.NEGATE -> {
                if (!operand.isError && operand != IntType) {
                    diagnostics.error(
                        "unary '-' requires an int operand but this expression has type " +
                            operand.displayName,
                        expression.operand.span,
                    )
                }
                IntType
            }

            UnaryOperator.NOT -> {
                if (!operand.isError && operand != BoolType) {
                    diagnostics.error(
                        "unary '!' requires a bool operand but this expression has type " +
                            operand.displayName,
                        expression.operand.span,
                    )
                }
                BoolType
            }
        }
    }

    private fun analyzeBinary(expression: BinaryExpression): NekoType {
        val left = analyzeExpression(expression.left)
        val right = analyzeExpression(expression.right)
        val operator = expression.operator

        if (left.isError || right.isError) return ErrorType

        return when (operator) {
            BinaryOperator.SUBTRACT, BinaryOperator.MULTIPLY,
            BinaryOperator.DIVIDE, BinaryOperator.REMAINDER,
            -> {
                requireInt(left, expression.left, operator)
                requireInt(right, expression.right, operator)
                IntType
            }

            BinaryOperator.ADD -> when {
                left == StringType && right == StringType -> StringType
                else -> {
                    requireInt(left, expression.left, operator)
                    requireInt(right, expression.right, operator)
                    IntType
                }
            }
            BinaryOperator.EQUAL, BinaryOperator.NOT_EQUAL -> {
                if (!left.isAssignableTo(right) && !right.isAssignableTo(left)) {
                    diagnostics.error(
                        "cannot compare a value of type ${left.displayName} with one of type " +
                            right.displayName,
                        expression.operatorSpan,
                    )
                    ErrorType
                } else {
                    BoolType
                }
            }

            BinaryOperator.LESS, BinaryOperator.LESS_EQUAL,
            BinaryOperator.GREATER, BinaryOperator.GREATER_EQUAL,
            -> {
                requireInt(left, expression.left, operator)
                requireInt(right, expression.right, operator)
                BoolType
            }

            BinaryOperator.AND, BinaryOperator.OR -> {
                requireBoolOperand(left, expression.left, operator)
                requireBoolOperand(right, expression.right, operator)
                BoolType
            }
        }
    }

    /**
     * Reports an operand of the wrong type, anchored at the operand itself.
     *
     * The operand is passed as an [Expression] rather than as a bare [Span] so that the diagnostic
     * cannot accidentally be pointed at the wrong sub-expression.
     */
    private fun requireInt(type: NekoType, operand: Expression, operator: BinaryOperator) {
        if (type.isError || type == IntType) return
        diagnostics.error(
            "operator '${operator.display}' requires int operands but this operand has type " +
                type.displayName,
            operand.span,
        )
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
                return analyzeConstruction(expression, callee)
            }

            // Not a function: it may be a variable or field holding a function value. When the name
            // resolves to nothing at all, say so in call terms — "unresolved reference" would leave
            // the reader unsure whether the call or the name was the problem.
            val value = lookup(callee.name)
            if (value == null) {
                diagnostics.error("there is no function named '${callee.name}'", callee.span)
                for (argument in expression.arguments) analyzeExpression(argument)
                return ErrorType
            }
            callee.resolution = value
            // The callee is a *value* of function type, so code generation has to know its type to
            // pick the interface to `invokeinterface` on. Analysing it as an expression would record
            // this too, but the name was already resolved by hand above.
            callee.resolvedType = value.type
            checkMemberReachable(value, callee.span)
            return callFunctionValue(expression, value.type, value.name)
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
        return callFunctionValue(expression, calleeType, callee.span.text)
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
        val namedClass = className?.let { classes[it] }
        if (namedClass != null) {
            callee.receiverIsClass = true
            callee.receiver.resolvedType = ClassType(namedClass.jvmInternalName, namedClass.name)
            return resolveMemberCall(expression, callee, namedClass, className, viaClass = true)
        }

        val receiverType = analyzeExpression(callee.receiver)

        if (receiverType.isError) {
            for (argument in expression.arguments) analyzeExpression(argument)
            return ErrorType
        }
        if (receiverType !is ClassType) {
            for (argument in expression.arguments) analyzeExpression(argument)
            diagnostics.error(
                "cannot call member '${callee.memberName}' on a value of type " +
                    "${receiverType.displayName}, which has no members",
                callee.memberSpan,
            )
            return ErrorType
        }

        val classSymbol = classOf(receiverType)
        if (classSymbol == null) {
            for (argument in expression.arguments) analyzeExpression(argument)
            diagnostics.error(
                "cannot call '${callee.memberName}': ${receiverType.displayName} is not declared " +
                    "in this file, so its members cannot be checked",
                callee.memberSpan,
            )
            return ErrorType
        }
        return resolveMemberCall(expression, callee, classSymbol, receiverType.displayName, viaClass = false)
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
        // A static call needs no receiver; an instance one does, and it must be evaluated once.
        if (!candidate.isStatic) expression.instanceReceiver = callee.receiver
        expression.instanceMethod = candidate

        // The receiver *is* the object, so an instance member reached through one is always
        // reachable — there is nothing for `checkMemberReachable` to report. It is only the
        // class-name form, and a bare name, that can find an instance member with no object in hand.
        if (viaClass) checkMemberReachable(candidate, callee.memberSpan)
        checkCallableNormally(candidate, callee.memberSpan)
        checkArguments(expression, candidate.parameterTypes, candidate.name)
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
        val type = resolveClassType(callee.name, callee.span)
        if (type !is ClassType) {
            for (argument in expression.arguments) analyzeExpression(argument)
            return ErrorType
        }

        val classSymbol = classes[callee.name]
        if (classSymbol == null) {
            for (argument in expression.arguments) analyzeExpression(argument)
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
        return type
    }

    private fun callFunctionValue(
        expression: CallExpression,
        calleeType: NekoType,
        calleeName: String,
    ): NekoType {
        if (calleeType !is FunctionType) {
            diagnostics.error(
                "'$calleeName' cannot be called because it is not a function; its type is " +
                    calleeType.displayName,
                expression.callee.span,
            )
            for (argument in expression.arguments) analyzeExpression(argument)
            return ErrorType
        }
        checkArguments(expression, calleeType.parameters, calleeName)
        return calleeType.returnType
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

    /** Built-in and user-declared functions named [name] that accept [arity] arguments. */    private fun functionCandidates(name: String, arity: Int): List<FunctionSymbol> {
        val builtin = builtins[name].orEmpty().filter { it.parameterTypes.size == arity }
        if (builtin.isNotEmpty()) return builtin
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
        val expectedFunction = expectedType as? FunctionType

        if (expectedFunction == null && expression.parameters.any { it.declaredType == null }) {
            diagnostics.error(
                "cannot infer the parameter types of this lambda: the surrounding context does not " +
                    "expect a function type, so annotate each parameter, as in " +
                    "'{ x: int, y: int -> ... }'",
                expression.span,
            )
        }
        if (expectedFunction != null && expectedFunction.arity != expression.parameters.size) {
            diagnostics.error(
                "this lambda declares ${expression.parameters.size} parameter" +
                    "${if (expression.parameters.size == 1) "" else "s"} but a function type with " +
                    "${expectedFunction.arity} parameter" +
                    "${if (expectedFunction.arity == 1) "" else "s"} is expected here",
                expression.span,
            )
        }

        val parameterTypes = expression.parameters.mapIndexed { index, parameter ->
            parameter.declaredType?.let { resolveType(it) }
                ?: expectedFunction?.parameters?.getOrNull(index)
                ?: ErrorType
        }
        expression.resolvedParameterTypes = parameterTypes

        val state = LambdaState(identity = Any())
        expression.identity = state.identity
        val outerLambda = currentLambda
        val outerFunction = currentFunction
        // A lambda body runs on a different object, so it has no `this`: naming an instance member
        // bare would silently read the wrong object, so the implicit receiver is withdrawn here.
        val outerImplicitThis = implicitThisClass
        currentLambda = state
        // `return` inside a lambda is an error (reported by `analyzeReturn`), so the enclosing
        // function must not stay visible here.
        currentFunction = null
        implicitThisClass = null

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
        currentLambda = outerLambda
        currentFunction = outerFunction
        implicitThisClass = outerImplicitThis

        val returnType = state.expectedReturnType ?: when {
            expectedFunction != null -> expectedFunction.returnType
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
        return FunctionType(parameterTypes, returnType)
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
        val className = (expression.receiver as? NameExpression)?.name
        val namedClass = className?.let { classes[it] }
        if (namedClass != null) {
            expression.receiverIsClass = true
            expression.receiver.resolvedType = ClassType(namedClass.jvmInternalName, namedClass.name)
            return staticMemberOf(namedClass, expression, namedClass.name)
        }

        val receiverType = analyzeExpression(expression.receiver)

        if (receiverType.isError) return ErrorType
        if (receiverType !is ClassType) {
            diagnostics.error(
                "cannot access member '${expression.memberName}' on a value of type " +
                    "${receiverType.displayName}, which has no members",
                expression.memberSpan,
            )
            return ErrorType
        }

        val classSymbol = classOf(receiverType)
        val field = classSymbol?.fields?.get(expression.memberName)
        if (field != null) {
            expression.resolvedField = field
            return field.type
        }

        val functions = classSymbol?.functions?.get(expression.memberName).orEmpty()
        if (functions.isNotEmpty()) {
            // A bare `obj.f` names a function without calling it. Neko has no method references,
            // and the sample never needs one, so say so plainly instead of guessing an overload.
            expression.resolvedFunction = functions.first()
            diagnostics.error(
                "'${expression.memberName}' is a function of ${receiverType.displayName}; write " +
                    "'${expression.receiver.span.text}.${expression.memberName}(...)' to call it",
                expression.memberSpan,
            )
            return ErrorType
        }

        diagnostics.error(
            "${receiverType.displayName} has no member named '${expression.memberName}'",
            expression.memberSpan,
        )
        return ErrorType
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
            return field.type
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
        )
        return ErrorType
    }

    // ------------------------------------------------------------ name lookup

    /** Resolves a bare identifier, reporting an unresolved reference when nothing matches. */
    private fun resolveName(expression: NameExpression): Symbol? {
        val symbol = lookup(expression.name)
        if (symbol == null) {
            diagnostics.error("unresolved reference '${expression.name}'", expression.span)
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
        (type as? ClassType)?.let { classes[it.simpleName] }

    private fun packagePrefix(): String = currentClass?.packagePrefix ?: ""

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
            "int" -> IntType
            "string" -> StringType
            "bool" -> BoolType
            "Object" -> ObjectType
            "Throwable" -> ThrowableType
            else -> {
                // A type parameter of the class being analysed stands for "whatever the caller
                // passes", which is exactly `Object` once the JVM erases it. That is all the
                // standard library's extractors need, since their bodies only forward values.
                val owner = currentClass
                if (owner != null && reference.name in owner.typeParameters) ObjectType
                else resolveClassType(reference.name, reference.nameSpan)
            }
        }

        is ArrayTypeReference -> ArrayType(resolveType(reference.element))
        is FunctionTypeReference -> FunctionType(
            parameters = reference.parameters.map { resolveType(it) },
            returnType = resolveType(reference.returnType),
        )
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
        declaredClass(name)?.let { return ClassType(it.jvmInternalName, name) }

        // A class the program does not declare but the JVM provides — `RuntimeException`,
        // `ArithmeticException`, `String` — is one a program has to be able to name in order to
        // extend or catch it. Whether it exists is answered by the platform rather than by a list, so
        // every `java.lang` class works, including ones added later.
        if (isPlatformClass(name)) return ClassType("java/lang/$name", name)

        if (!name.first().isUpperCase()) {
            diagnostics.error(
                "unknown type '$name': Neko has 'int', 'string', 'bool', 'Object' and " +
                    "'Throwable', array types such as 'string[]', function types such as " +
                    "'[int] -> int', and class types, whose names start with a capital letter",
                span,
            )
            return ErrorType
        }

        // No declaration to read an internal name from, so the package has to be picked: the first
        // import if the file wrote one, and otherwise the file's own package.
        return ClassType(importedPrefix() + name, name)
    }

    /**
     * The class named [simpleName] the program declares, or `null` when nothing does.
     *
     * Every declaration is known by its simple name, whichever package it lives in, because the unit
     * of compilation is the whole program: a name that two packages both declare is rejected when
     * the classes are declared rather than silently resolved to one of them, so this lookup can
     * never pick the wrong class.
     */
    private fun declaredClass(simpleName: String): ClassSymbol? = classes[simpleName]

    /**
     * The package prefix an undeclared class name belongs to: the current file's first `import`, or
     * its own `locate` package when it wrote none.
     *
     * Taking the first import rather than searching them is what makes resolution a plain reading of
     * the file: the packages of an undeclared class cannot be inspected, so there is nothing to
     * search, and a name found in none of them is the same failure wherever it was looked for.
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
         * The standard library declarations `raise` and `try` are lowered against.
         *
         * A program that uses either needs every one of these present, because the generated code
         * names them by their internal names: `EarlyEmitException` is what a `raise` throws, and
         * `EmitExtractor` is what a `try` extractor implements.
         */
        val STANDARD_LIBRARY_TYPES = listOf("EarlyEmitException", "EmitExtractor")
    }
}

// --------------------------------------------------------------------- helpers

/** Whether a symbol is a declaration a new local declaration could shadow. */
private fun Symbol.isDeclaration(): Boolean = this is LocalSymbol || this is FieldSymbol

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
 * Types with a well-defined zero value, which may therefore be declared without an initializer.
 *
 * `int` and `bool` default to `0`/`false`; every reference type — a class, an array, a function
 * value — defaults to `null`, exactly as the JVM's field initialization does. That is what lets the
 * standard library declare `private val data: Object[]` and assign it in the constructor.
 */
private val NekoType.hasImplicitDefault: Boolean
    get() = this == IntType || this == BoolType || isReferenceType || isError

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
    // A `raise` transfers control to the caller's extractor by throwing, so it is terminating too.
    is RaiseStatement -> true
    is BlockStatement -> alwaysReturns()
    is IfStatement -> elseBranch != null && thenBranch.alwaysReturns() && elseBranch.alwaysReturns()
    is WhenStatement -> hasElseBranch && branches.all { it.body.alwaysReturns() }
    is WhileStatement -> false
    // A `try` with a `finally` returns only if every path out of it does, which is more than a
    // conservative test can tell; reporting "maybe falls through" is the safe answer.
    is YieldStatement, is VariableDeclarationStatement, is AssignmentStatement,
    is ExpressionStatement, is TryStatement, is TryCatchStatement, is IndexAssignmentStatement,
    -> false
}

/**
 * Whether execution can never reach the statement after this one.
 *
 * Narrower than [alwaysReturns]: an `if` without an `else` still lets control through, so only the
 * statements that unconditionally transfer control count here.
 */
private fun Statement.terminatesFlow(): Boolean = when (this) {
    is ReturnStatement, is RaiseStatement -> true
    is BlockStatement -> statements.any { it.terminatesFlow() }
    is IfStatement -> elseBranch != null && thenBranch.terminatesFlow() && elseBranch.terminatesFlow()
    is WhenStatement -> hasElseBranch && branches.all { it.body.terminatesFlow() }
    else -> false
}

/** The words used when reporting a statement whose successors are unreachable. */
private fun Statement.describeTerminator(): String = when (this) {
    is ReturnStatement -> "'return'"
    is RaiseStatement -> "'raise'"
    is BlockStatement -> "block"
    is IfStatement -> "'if'"
    is WhenStatement -> "'when'"
    else -> "statement"
}

/**
 * Whether this statement can `raise`, directly or anywhere inside it.
 * Used to reject a function that declares an `emit` clause but has no way to reach it: such a
 * function can only return normally, so a caller's `try` would never run its extractor.
 */
private fun Statement.containsRaise(): Boolean = when (this) {
    is RaiseStatement -> true
    is BlockStatement -> statements.any { it.containsRaise() }
    is IfStatement -> thenBranch.containsRaise() || elseBranch?.containsRaise() == true
    is WhenStatement -> branches.any { it.body.containsRaise() }
    is WhileStatement -> body.containsRaise()
    // A `raise` in a `try` body belongs to that body's own function; the values it carries are
    // handed to this statement's extractor instead.
    is TryStatement -> extractorBody.statements.any { it.containsRaise() }
    is TryCatchStatement ->
        body.statements.any { it.containsRaise() } ||
            catches.any { clause -> clause.body.statements.any { it.containsRaise() } } ||
            finallyBlock?.statements?.any { it.containsRaise() } == true
    else -> false
}
