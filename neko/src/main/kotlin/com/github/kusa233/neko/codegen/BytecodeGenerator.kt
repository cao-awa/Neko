package com.github.kusa233.neko.codegen

import com.github.kusa233.neko.ast.*
import com.github.kusa233.neko.diagnostics.DiagnosticBag
import com.github.kusa233.neko.symbols.ClassSymbol
import com.github.kusa233.neko.symbols.FieldSymbol
import com.github.kusa233.neko.symbols.FunctionSymbol
import com.github.kusa233.neko.symbols.LocalSymbol
import com.github.kusa233.neko.symbols.Symbol
import com.github.kusa233.neko.types.*
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*

/**
 * Lowers the analysed AST to JVM class files using ASM.
 *
 * ### How Neko maps onto the JVM
 *
 *  * **A class has static and instance members.** A member written `static` is emitted `static` and
 *    reached by class name; a member written without it belongs to an instance, so it is emitted as
 *    an ordinary instance field or method and reached through an object 鈥擿obj.f`, or an implicit
 *    `this` from inside another instance member of the same class. Instance field initializers run
 *    in `<init>`, static ones in `<clinit>`. `fun main(args: string[])` is a JVM entry point only
 *    when the source declares it `static`, exactly as the source reads.
 *  * **Values.** `byte`鈫抈B`, `short`鈫抈S`, `char`鈫抈C`, `int`鈫抈I`, `long`鈫抈J`, `float`鈫抈F`,
 *    `double`鈫抈D`, `bool`鈫抈Z`, `string`鈫抈Ljava/lang/String;`, `T[]`鈫抈[<T>`, and a function type鈫抋
 *    generated interface (see below). `byte`, `short` and `char` are `int`s to the JVM wherever a
 *    value is on the stack or in a local, and are narrowed back with `I2B`/`I2S`/`I2C` wherever one is
 *    *stored*; `long` and `double` occupy two local slots rather than one.
 *  * **A function type becomes an interface, and a lambda becomes a class.** For every distinct
 *    function type used, one interface `Neko$Function$n` is emitted with a single method
 *    `invoke`. Each lambda literal becomes a `final` class implementing that interface, with its
 *    captured variables as `final` fields; calling a function value is an `invokeinterface`
 *    dispatch. This is the classic closure-class lowering.
 *  * **Capture is by value.** A lambda copies each referenced enclosing variable into a field. The
 *    analyzer rejects assignment to such a variable, which is what keeps that copy from silently
 *    diverging from the original.
 *  * **`raise` throws, `try` catches.** A `raise e1, e2` boxes its values into an `Object[]`,
 *    wraps them in the standard library's `EarlyEmitException`, and throws it; the construct that
 *    called the raising function catches it and hands the array to its extractor. The extractor
 *    literal is compiled like a lambda, implementing a standard library interface named for the
 *    number of values it receives.
 *  * **A `try` may be worth a value.** Its two forms differ only in whether the extractor's `invoke`
 *    answers something: the statement form implements `EmitExtractorN` and discards what it finds,
 *    while `r: int = try f() { a -> yield 1 }` implements `EmitExtractorWithResultN`, whose `invoke`
 *    answers the `yield` 鈥攂oxed, since the interface erases `R` to `Object`. Both forms then fill one
 *    slot from whichever path ran: the call's own return value when it returned, or `doEmit`'s answer
 *    when it raised.
 *
 * The generator assumes the analyzer ran without errors; where a node is missing its recorded type
 * it emits [ErrorType]'s placeholder descriptor rather than crashing, so a partially analysed tree
 * still produces a class file the user can inspect.
 */
class BytecodeGenerator(
    private val program: NekoProgram,
    private val classes: Map<String, ClassSymbol>,
    /**
     * The parameter symbols the analyzer created, keyed by declaration.
     *
     * These must be the *same instances* the analyzer stored on `NameExpression.resolution`;
     * symbols are compared by identity when matching a reference to its local slot, so recreating
     * them here would silently fail to resolve every parameter read.
     */
    private val parametersByFunction: Map<FunctionDeclaration, List<LocalSymbol>>,
    private val diagnostics: DiagnosticBag,
) {

    /** Every class file this run produced, keyed by JVM internal name. */
    private val output = LinkedHashMap<String, ByteArray>()

    /** One generated interface per distinct function type, in first-use order. */
    private val functionInterfaces = LinkedHashMap<FunctionType, String>()

    private var lambdaCounter = 0

    /** The internal name of the class whose method is currently being emitted. */
    private var currentOwner = ""

    /** The class symbol whose instance members the current method may reach with implicit `this`. */
    private var currentClass: ClassSymbol? = null

    /**
     * The file whose class is currently being generated.
     *
     * Only the `SourceFile` attribute of the class file needs it: the debug name a stack trace shows
     * should be the `.neko` file the class was declared in, and each file in a program is its own.
     */
    private var currentFile: NekoFile? = null

    /** Generates all class files: one per Neko class, in the order the files were given. */
    fun generate(): Map<String, ByteArray> {
        for (declaration in program.classes) {
            val symbol = classes[declaration.name] ?: continue
            if (declaration.symbol !== symbol) continue
            generateClass(declaration, symbol)
        }

        // Function-type interfaces are discovered lazily while emitting bodies, so emit them last.
        emitFunctionInterfaces()
        return output
    }

    /**
     * Static builder methods to emit, grouped by the class that declares them.
     *
     * A `raise` needs a method that boxes its values into an `Object[]`, and that method belongs to
     * whichever class the `raise` was compiled into 鈥攖he enclosing Neko class, or a generated
     * extractor class when the `raise` appears inside an extractor body. Registration is keyed by
     * owner so each writer gets exactly the builders it must declare, drained just before that
     * writer is finished.
     */
    private val builderRegistrations = LinkedHashMap<String, MutableList<Pair<RaiseStatement, String>>>()

    /** Numbers the builders within one function, so their names are stable and unique. */
    private val builderCounters = LinkedHashMap<String, Int>()

    /** Registers a builder for [statement] in [owner] and returns the name it will be emitted as. */
    private fun registerBuilder(statement: RaiseStatement, owner: String, functionName: String): String {
        val index = builderCounters.getOrDefault(functionName, 0)
        builderCounters[functionName] = index + 1
        val name = "$functionName\$emit\$$index"
        builderRegistrations.getOrPut(owner) { mutableListOf() } += statement to name
        statement.builderName = name
        statement.builderOwner = owner
        return name
    }

    /** Emits and clears every builder registered for [owner], before its writer is finished. */
    private fun drainBuilders(writer: ClassWriter, owner: String) {
        val pending = builderRegistrations.remove(owner) ?: return
        for ((statement, name) in pending) emitBuilder(writer, statement, name)
    }

    /**
     * The internal name of a standard library class, looked up in the program.
     *
     * The library is written in Neko and compiled alongside the program, so `EarlyEmitException`
     * is a class this very run will emit 鈥攊ts internal name comes from the declaration rather than
     * from an assumption about the package. The fallback keeps code generation working when the
     * library is absent, so the reader sees the analyzer's "the library is missing" diagnostic
     * rather than a crash here.
     */
    private fun standardLibraryClass(simpleName: String): String =
        classes[simpleName]?.jvmInternalName ?: (currentClass?.packagePrefix ?: "") + simpleName

    /** The standard library's exception that carries raised values. */
    private fun earlyEmitExceptionClass(): String = standardLibraryClass(EARLY_EMIT_EXCEPTION_CLASS)

    /** The standard library's base interface every `EmitExtractorN` extends. */
    private fun emitExtractorBaseClass(): String = standardLibraryClass(EMIT_EXTRACTOR_CLASS)

    /** The `EmitExtractorN` interface for [arity], which the standard library provides. */
    private fun emitExtractorInterface(arity: Int): String = standardLibraryClass("EmitExtractor$arity")

    /**
     * The `EmitExtractorWithResultN` interface for [arity], used when the extractor writes `yield`.
     *
     * The two families differ only in what `invoke` answers 鈥攏othing, or the value the `try`
     * produces 鈥攕o which one a `try` implements is decided by whether its body yields.
     */
    private fun emitExtractorWithResultInterface(arity: Int): String =
        standardLibraryClass("$EMIT_EXTRACTOR_WITH_RESULT_PREFIX$arity")

    /** Descriptor of `Object[]`, the carrier a `raise` boxes its values into. */
    private fun objectArrayType(): String = "[Ljava/lang/Object;"

    private fun packagePrefix(): String = currentClass?.packagePrefix ?: ""

    /**
     * Whether any class in the program extends [declaration].
     *
     * A class nothing extends is emitted `final`, which is the accurate access flag and lets the JVM
     * optimize calls into it. A class that *is* extended must not be: `final` on a superclass is an
     * `IncompatibleClassChangeError` the moment the subclass is loaded, and it is not something the
     * source can be blamed for 鈥攖he program never said `final`.
     */
    private fun isExtended(symbol: ClassSymbol): Boolean =
        classes.values.any { it.superclassSymbol === symbol }

    // ----------------------------------------------------------------- a class

    private fun generateClass(declaration: ClassDeclaration, symbol: ClassSymbol) {
        val internalName = symbol.jvmInternalName
        val previousOwner = currentOwner
        val previousClass = currentClass
        val previousFile = currentFile
        currentOwner = internalName
        currentClass = symbol
        currentFile = program.fileOf(declaration)

        val writer = NekoClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)

        // An interface is `abstract interface`, never `final`, and extends `Object` implicitly 鈥攊t
        // must not name a superclass. A class is `super`, and extends whatever the source named,
        // defaulting to `Object`. It is `final` only when nothing extends it, because the JVM
        // refuses to load a subclass of a final class.
        //
        // An enum is `final` for a stronger reason: its constants are the whole of its instances, so
        // a subclass would be a value the enum never named. `ACC_ENUM` is what tells the JVM that, and
        // it is also what makes `Enum.valueOf` and `values()` well behaved.
        val superName = when {
            declaration.isInterface -> "java/lang/Object"
            declaration.isMiao -> "java/lang/Enum"
            else -> symbol.superclass?.internalName ?: "java/lang/Object"
        }
        val interfaceNames = symbol.interfaces
            .map { it.internalName }
            .takeIf { it.isNotEmpty() }
            ?.toTypedArray()

        val access = when {
            declaration.isInterface -> ACC_PUBLIC or ACC_ABSTRACT or ACC_INTERFACE
            declaration.isMiao -> ACC_PUBLIC or ACC_FINAL or ACC_SUPER or ACC_ENUM
            isExtended(symbol) -> ACC_PUBLIC or ACC_SUPER
            else -> ACC_PUBLIC or ACC_FINAL or ACC_SUPER
        }
        // A generic class records its type parameters and its supertypes, so a reader sees
        // `EmitExtractor2<T1, T2> extends EmitExtractor` rather than a bare erasure.
        val signature = if (declaration.typeParameters.isEmpty()) null else {
            buildString {
                append('<')
                for (name in declaration.typeParameters) append(name).append(":Ljava/lang/Object;")
                append('>')
                append('L').append(superName).append(';')
                for (implemented in symbol.interfaces) append('L').append(implemented.internalName).append(';')
            }
        }

        writer.visit(V1_8, access, internalName, signature, superName, interfaceNames)
        writer.visitSource(currentFile.sourceName(), null)

        for (field in declaration.fields) {
            val fieldSymbol = field.symbol ?: continue
            val descriptor = descriptorOf(fieldSymbol.type)
            fieldSymbol.jvmDescriptor = descriptor
            // An interface's fields are constants shared by every implementor, so the JVM requires
            // them to be `public static final`; the source cannot say otherwise.
            val access = if (declaration.isInterface) {
                ACC_PUBLIC or ACC_STATIC or ACC_FINAL
            } else {
                fieldAccess(fieldSymbol)
            }
            writer.visitField(access, field.name, descriptor, null, null).visitEnd()
        }

        // An enum's constants are fields of the enum's own type, `public static final`, and each
        // carries `ACC_ENUM` so a reflective reader sees them as constants rather than as ordinary
        // static fields. They are not in `declaration.fields` 鈥?the reader wrote a list of names, not
        // field declarations 鈥?so they are emitted from the entries.
        if (declaration.isMiao) emitEnumEntryFields(writer, declaration, symbol)

        // A `purr` block needs a `StampedLock` to hold, and the source cannot declare one: it lives in
        // `java.util.concurrent.locks`, which Neko cannot name. The field is created here, only for the
        // locks the class actually uses.
        emitPurrLockFields(writer, declaration, symbol)

        val staticFields = declaration.fields.filter { it.isStatic }
        val instanceFields = declaration.fields.filterNot { it.isStatic }
        // An enum's `<clinit>` has to do more than run field initializers 鈥?it builds every constant,
        // and the array `values()` answers 鈥?so it is written on its own and replaces the generic
        // one. Both are static-initialisation work, but only the enum one has to create instances.
        if (declaration.isMiao) {
            emitEnumInitializer(writer, declaration, symbol, instanceFields)
        } else {
            emitStaticInitializer(writer, staticFields, symbol, declaration)
        }
        // Every class gets a constructor, because an instance member is only reachable through an
        // object, and this is where that object's own field initializers run. An interface is never
        // instantiated, so it gets none 鈥攖he JVM rejects one that has a constructor.
        //
        // An enum's constructor is emitted by [emitEnumInitializer], because it is a private
        // constructor taking the header fields plus the name and position `Enum` is handed.
        if (!declaration.isInterface && !declaration.isMiao) {
            emitConstructors(writer, declaration, instanceFields, symbol)
        }

        for (function in declaration.functions) {
            val functionSymbol = function.symbol ?: continue
            emitFunction(writer, function, functionSymbol, internalName)
        }

        // `values()` and `valueOf(string)` are what `java.lang.Enum` gives a Java enum, and what a
        // reader of a `miao class` expects to find on it.
        if (declaration.isMiao) emitEnumMethods(writer, declaration, symbol)

        // A `meow class` derives `equals`, `hashCode` and `toString` from its fields. They are emitted
        // as raw bytecode rather than lowered from a Neko body because the language cannot express any
        // of them: `equals` has to compare a value whose class it only learns at run time, `hashCode`
        // has to fold a value into an `int`, and `toString` has to read a field of a type the source
        // never named. None of the three has a body to write them in.
        if (declaration.isMeow) emitDerivedObjectMethods(writer, declaration, symbol)

        emitMainBridge(writer, declaration, symbol)

        // A `raise` needs a builder in whichever class it was compiled into, so any that this class
        // owns are written now, while the writer is still open.
        drainBuilders(writer, internalName)

        writer.visitEnd()
        output[internalName] = writer.toByteArray()

        currentOwner = previousOwner
        currentClass = previousClass
        currentFile = previousFile
    }

    private fun fieldAccess(field: FieldSymbol): Int {
        val visibility = when (field.modifier) {
            "private" -> ACC_PRIVATE
            "protected" -> ACC_PROTECTED
            else -> ACC_PUBLIC
        }
        val mutability = if (field.isMutable) 0 else ACC_FINAL
        val static = if (field.isStatic) ACC_STATIC else 0
        return visibility or static or mutability
    }

    /** `<clinit>`: evaluates every `static` field initializer in declaration order. */
    private fun emitStaticInitializer(
        writer: ClassWriter,
        fields: List<FieldDeclaration>,
        symbol: ClassSymbol,
        declaration: ClassDeclaration,
    ) {
        val initialized = fields.filter { it.initializer != null && it.symbol != null }
        val needsClassLock = PurrLock.CLASS in locksUsedBy(declaration)
        if (initialized.isEmpty() && !needsClassLock) return

        val mv = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null)
        mv.visitCode()
        val scope = MethodScope(
            owner = symbol.jvmInternalName,
            mv = mv,
            lambdaIdentity = null,
            firstSlot = 0,
            static = true,
        )

        // The class lock is built first, so a `static` initializer may use it 鈥?a `purrcls` block is
        // legal in a `static` function, and that function's body runs after this. Being first also
        // means it is in place before any constant's arguments run in an enum's own `<clinit>`.
        if (needsClassLock) {
            mv.visitTypeInsn(NEW, STAMPED_LOCK_CLASS)
            mv.visitInsn(DUP)
            mv.visitMethodInsn(INVOKESPECIAL, STAMPED_LOCK_CLASS, "<init>", "()V", false)
            mv.visitFieldInsn(
                PUTSTATIC,
                symbol.jvmInternalName,
                purrLockFieldName(PurrLock.CLASS),
                "L$STAMPED_LOCK_CLASS;",
            )
        }

        for (field in initialized) {
            val fieldSymbol = field.symbol!!
            val initializer = field.initializer!!
            markLine(mv, initializer)
            emitExpression(initializer, scope)
            emitStoreValue(initializer.type, fieldSymbol.type, mv)
            mv.visitFieldInsn(
                PUTSTATIC,
                symbol.jvmInternalName,
                fieldSymbol.name,
                descriptorOf(fieldSymbol.type),
            )
        }

        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    // ------------------------------------------------------------------- purr

    /**
     * The `StampedLock` field name for [lockType].
     *
     * The two locks live in separate fields because they are separate locks: one per object, one per
     * class. One field could not serve both, since the class lock has to be `static` to be shared.
     *
     * The `$` prefix marks them as compiler-created, the way `$VALUES` does for an enum, so a reader
     * of the class file can tell they were not written in the source.
     */
    private fun purrLockFieldName(lockType: PurrLock): String =
        if (lockType == PurrLock.CLASS) "\$classLock" else "\$lock"

    /**
     * Emits the `StampedLock` fields a class' `purr` blocks need.
     *
     * A field exists only when a block uses it: a class with no `purr` gets no lock, and one that only
     * guards instance state gets no class lock. Adding a `StampedLock` to every class would cost an
     * allocation per object for a feature most classes never use.
     *
     * The naming and the reason they are two fields is in [purrLockFieldName]. Initialization happens
     * elsewhere 鈥?the instance one in the constructor and the class one in `<clinit>` 鈥?because
     * `StampedLock` lives in `java.util.concurrent.locks`, which a Neko source file cannot name, so
     * neither could be written as a field initializer.
     */
    private fun emitPurrLockFields(
        writer: ClassWriter,
        declaration: ClassDeclaration,
        symbol: ClassSymbol,
    ) {
        for (lockType in locksUsedBy(declaration)) {
            val access = ACC_PRIVATE or ACC_FINAL or
                if (lockType == PurrLock.CLASS) ACC_STATIC else 0
            writer.visitField(access, purrLockFieldName(lockType), "L$STAMPED_LOCK_CLASS;", null, null)
                .visitEnd()
        }
    }

    /**
     * Which locks [declaration] needs, from the `purr` blocks written anywhere in its bodies.
     *
     * A block inside a lambda counts: the lambda's own class holds the block, but it reaches the field
     * through the enclosing instance it captures, so the field has to exist on *this* class. A `purr`
     * inside a `nya` block counts for a different reason 鈥?those blocks are emitted inline, in this
     * very method.
     */
    private fun locksUsedBy(declaration: ClassDeclaration): Set<PurrLock> {
        val used = linkedSetOf<PurrLock>()
        for (function in declaration.functions) collectPurrLocks(function.body, used)
        for (constructor in declaration.constructors) collectPurrLocks(constructor.body, used)
        for (field in declaration.fields) collectPurrLocks(field.initializer, used)
        return used
    }

    private fun collectPurrLocks(block: BlockStatement?, used: MutableSet<PurrLock>) {
        block?.statements?.forEach { collectPurrLocks(it, used) }
    }

    private fun collectPurrLocks(statement: Statement?, used: MutableSet<PurrLock>) {
        when (statement) {
            null -> Unit
            is PurrStatement -> {
                used += statement.lock
                collectPurrLocks(statement.body, used)
            }

            is BlockStatement -> statement.statements.forEach { collectPurrLocks(it, used) }
            is IfStatement -> {
                collectPurrLocks(statement.thenBranch, used)
                collectPurrLocks(statement.elseBranch, used)
            }

            is WhileStatement -> collectPurrLocks(statement.body, used)
            is WhenStatement -> statement.branches.forEach { collectPurrLocks(it.body, used) }
            is TryStatement -> collectPurrLocks(statement.extractorBody, used)
            is ExpressionStatement -> collectPurrLocks(statement.expression, used)
            is VariableDeclarationStatement -> collectPurrLocks(statement.initializer, used)
            is ReturnStatement -> collectPurrLocks(statement.value, used)
            is YieldStatement -> collectPurrLocks(statement.value, used)
            is AssignmentStatement -> collectPurrLocks(statement.value, used)
            is IndexAssignmentStatement -> collectPurrLocks(statement.value, used)
            is RaiseStatement -> statement.values.forEach { collectPurrLocks(it, used) }
        }
    }

    private fun collectPurrLocks(expression: Expression?, used: MutableSet<PurrLock>) {
        when (expression) {
            null -> Unit
            is LambdaExpression -> collectPurrLocks(expression.body, used)
            is NyaExpression -> {
                collectPurrLocks(expression.failureBlock, used)
                collectPurrLocks(expression.elseBlock, used)
            }

            is TryExpression -> collectPurrLocks(expression.extractorBody, used)
            is CallExpression -> {
                collectPurrLocks(expression.callee, used)
                expression.arguments.forEach { collectPurrLocks(it, used) }
            }

            is BinaryExpression -> {
                collectPurrLocks(expression.left, used)
                collectPurrLocks(expression.right, used)
            }

            is UnaryExpression -> collectPurrLocks(expression.operand, used)
            is MemberAccessExpression -> collectPurrLocks(expression.receiver, used)
            is IndexExpression -> {
                collectPurrLocks(expression.array, used)
                collectPurrLocks(expression.index, used)
            }

            is ChainedComparisonExpression -> expression.operands.forEach { collectPurrLocks(it, used) }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ an enum

    /**
     * One `public static final` field per constant, each carrying `ACC_ENUM`.
     *
     * `ACC_ENUM` is what the JVM and reflection read to tell a constant from an ordinary static
     * field: `EnumSet`, `values()` and a `switch` over an enum all rely on it, and a field without it
     * would still work as a value but would not *be* an enum constant to anything outside.
     *
     * The descriptor is the enum's own type, so `Color.RED` has type `Color` and fits nowhere else.
     */
    private fun emitEnumEntryFields(
        writer: ClassWriter,
        declaration: ClassDeclaration,
        symbol: ClassSymbol,
    ) {
        val descriptor = "L${symbol.jvmInternalName};"
        for (entry in declaration.entries) {
            entry.symbol?.jvmDescriptor = descriptor
            writer.visitField(
                ACC_PUBLIC or ACC_STATIC or ACC_FINAL or ACC_ENUM,
                entry.name,
                descriptor,
                null,
                null,
            ).visitEnd()
        }
    }

    /**
     * The enum's `<clinit>`, its one private constructor, and the `$VALUES` array behind `values()`.
     *
     * This is the shape `javac` emits for an enum, and it is not negotiable: `java.lang.Enum`'s
     * constructor takes the constant's name and its position, the JVM refuses an enum class whose
     * constants are not `ACC_ENUM` fields, and `values()` has to answer a fresh copy of the array so a
     * caller cannot write through it into the enum.
     *
     * ```
     *   <clinit>:
     *     RED   = new Color("RED",   0)
     *     GREEN = new Color("GREEN", 1)
     *     $VALUES = [RED, GREEN]
     *     $VALUES.getClass()  // nothing; the array is built with ANEWARRAY
     * ```
     *
     * The constructor takes the header's fields, so a constant written `RED(1)` passes `1` through to
     * the field the header declared, exactly as a `meow class` constructor parameter would.
     */
    private fun emitEnumInitializer(
        writer: ClassWriter,
        declaration: ClassDeclaration,
        symbol: ClassSymbol,
        instanceFields: List<FieldDeclaration>,
    ) {
        val internalName = symbol.jvmInternalName
        val headerFields = instanceFields.filter { it.symbol != null }
        val parameterTypes = headerFields.map { it.symbol!!.type }
        val constructorDescriptor = buildString {
            append('(')
            for (type in parameterTypes) append(descriptorOf(type))
            append("Ljava/lang/String;I)V")
        }

        // The constructor. `private`, because a constant is the only instance an enum may have: a
        // public one would let a caller build a value the enum never named, which is exactly what the
        // declaration exists to prevent.
        val constructor = writer.visitMethod(ACC_PRIVATE, "<init>", constructorDescriptor, null, null)
        constructor.visitCode()
        constructor.visitVarInsn(ALOAD, 0)
        constructor.visitVarInsn(ALOAD, parameterTypes.size + 1)
        constructor.visitVarInsn(ILOAD, parameterTypes.size + 2)
        constructor.visitMethodInsn(
            INVOKESPECIAL,
            "java/lang/Enum",
            "<init>",
            "(Ljava/lang/String;I)V",
            false,
        )
        // The header's fields come from the parameters, which sit after `this` and before the name
        // and position `Enum` takes.
        var slot = 1
        for (field in headerFields) {
            val fieldSymbol = field.symbol!!
            constructor.visitVarInsn(ALOAD, 0)
            constructor.visitVarInsn(loadOpcode(fieldSymbol.type), slot)
            constructor.visitFieldInsn(
                PUTFIELD,
                internalName,
                fieldSymbol.name,
                descriptorOf(fieldSymbol.type),
            )
            slot += BuiltinTypes.slotCountOf(fieldSymbol.type)
        }
        // A `purr` in one of the enum's own methods still needs its lock, and this private constructor
        // is the only one an enum has 鈥?so the instance lock is built here, exactly as
        // `emitConstructors` would do it.
        emitInstancePurrLockInitializer(constructor, declaration)
        constructor.visitInsn(RETURN)
        constructor.visitMaxs(0, 0)
        constructor.visitEnd()

        // `private static final $VALUES: Color[]`, the array `values()` copies. It is not a field the
        // reader wrote, and it is deliberately not public: `values()` hands out a clone so that
        // writing into the answer cannot corrupt the enum.
        writer.visitField(
            ACC_PRIVATE or ACC_STATIC or ACC_FINAL or ACC_SYNTHETIC,
            VALUES_FIELD,
            "[L$internalName;",
            null,
            null,
        ).visitEnd()

        val mv = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null)
        mv.visitCode()
        val scope = MethodScope(
            owner = internalName,
            mv = mv,
            lambdaIdentity = null,
            firstSlot = 0,
            static = true,
        )

        // A `purrcls` block in one of the enum's methods takes the class lock, which is built here.
        // It goes first, before the constant arguments run, so the lock is in place if one of them
        // reaches a method that guards on it.
        if (PurrLock.CLASS in locksUsedBy(declaration)) {
            mv.visitTypeInsn(NEW, STAMPED_LOCK_CLASS)
            mv.visitInsn(DUP)
            mv.visitMethodInsn(INVOKESPECIAL, STAMPED_LOCK_CLASS, "<init>", "()V", false)
            mv.visitFieldInsn(
                PUTSTATIC,
                internalName,
                purrLockFieldName(PurrLock.CLASS),
                "L$STAMPED_LOCK_CLASS;",
            )
        }

        // Any `static` fields the reader declared are initialized first, in source order, so a
        // constant's arguments can read them: `miao class Code { OK(MIN) }` needs `MIN` set.
        for (field in declaration.fields.filter { it.isStatic && it.initializer != null && it.symbol != null }) {
            val fieldSymbol = field.symbol!!
            val initializer = field.initializer!!
            markLine(mv, initializer)
            emitExpression(initializer, scope)
            emitStoreValue(initializer.type, fieldSymbol.type, mv)
            mv.visitFieldInsn(PUTSTATIC, internalName, fieldSymbol.name, descriptorOf(fieldSymbol.type))
        }

        // Each constant: `RED = new Color("RED", 0, <its arguments>)`.
        for ((ordinal, entry) in declaration.entries.withIndex()) {
            val entrySymbol = entry.symbol ?: continue
            markLine(mv, entry)

            mv.visitTypeInsn(NEW, internalName)
            mv.visitInsn(DUP)
            // The arguments come first, then the name and position: the constructor is
            // `(header fields..., String, int)`, so `Enum` is handed its two last.
            for (argument in entry.arguments) emitExpression(argument, scope)
            mv.visitLdcInsn(entry.name)
            mv.visitLdcInsn(ordinal)
            mv.visitMethodInsn(INVOKESPECIAL, internalName, "<init>", constructorDescriptor, false)
            mv.visitFieldInsn(
                PUTSTATIC,
                internalName,
                entrySymbol.name,
                "L$internalName;",
            )
        }

        // `$VALUES = [RED, GREEN, ...]`, in declaration order, which is what `ordinal()` agrees with.
        mv.visitLdcInsn(declaration.entries.size)
        mv.visitTypeInsn(ANEWARRAY, internalName)
        for ((ordinal, entry) in declaration.entries.withIndex()) {
            val entrySymbol = entry.symbol ?: continue
            mv.visitInsn(DUP)
            mv.visitLdcInsn(ordinal)
            mv.visitFieldInsn(GETSTATIC, internalName, entrySymbol.name, "L$internalName;")
            mv.visitInsn(AASTORE)
        }
        mv.visitFieldInsn(PUTSTATIC, internalName, VALUES_FIELD, "[L$internalName;")

        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * `values()` and `valueOf(string)`, the two static methods a Java enum has.
     *
     * `values()` answers a **copy** of `$VALUES`. Handing out the field itself would let a caller
     * write into the enum's own array 鈥?`Color.values()[0] = Color.GREEN` would then make every later
     * `values()` lie 鈥?which is why the clone is not merely an optimisation.
     *
     * `valueOf` compares against each constant's `name()` and throws `IllegalArgumentException` on a
     * miss, which is the behaviour a Java caller expects rather than returning `null`.
     */
    private fun emitEnumMethods(
        writer: ClassWriter,
        declaration: ClassDeclaration,
        symbol: ClassSymbol,
    ) {
        val internalName = symbol.jvmInternalName
        val arrayDescriptor = "[L$internalName;"

        writer.visitMethod(ACC_PUBLIC or ACC_STATIC, "values", "()$arrayDescriptor", null, null).apply {
            visitCode()
            visitFieldInsn(GETSTATIC, internalName, VALUES_FIELD, arrayDescriptor)
            visitMethodInsn(INVOKEVIRTUAL, arrayDescriptor, "clone", "()Ljava/lang/Object;", false)
            visitTypeInsn(CHECKCAST, arrayDescriptor)
            visitInsn(ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        writer.visitMethod(
            ACC_PUBLIC or ACC_STATIC,
            "valueOf",
            "(Ljava/lang/String;)L$internalName;",
            null,
            null,
        ).apply {
            visitCode()
            // `Enum.valueOf` already does exactly this for any enum class, so the work is delegated
            // rather than repeated: it looks the name up and throws `IllegalArgumentException` when
            // there is no such constant.
            visitLdcInsn(org.objectweb.asm.Type.getObjectType(internalName))
            visitVarInsn(ALOAD, 0)
            visitMethodInsn(
                INVOKESTATIC,
                "java/lang/Enum",
                "valueOf",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;",
                false,
            )
            visitTypeInsn(CHECKCAST, internalName)
            visitInsn(ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    /**
     * Emits one `<init>` per declared constructor, or a single default one when the class declares
     * none.
     *
     * Every constructor does the same three things in order: call the superclass' no-argument
     * constructor, run the instance field initializers, then run its own body. Neko has no
     * `super(...)` syntax, so the superclass call is always the no-argument one, which the analyzer
     * checks exists.
     */
    private fun emitConstructors(
        writer: ClassWriter,
        declaration: ClassDeclaration,
        fields: List<FieldDeclaration>,
        symbol: ClassSymbol,
    ) {
        val superName = symbol.superclass?.internalName ?: "java/lang/Object"

        if (declaration.constructors.isEmpty()) {
            val mv = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
            mv.visitCode()
            mv.visitVarInsn(ALOAD, 0)
            mv.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false)
            emitFieldInitializers(mv, fields, symbol)
            emitInstancePurrLockInitializer(mv, declaration)
            mv.visitInsn(RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
            return
        }

        for (constructor in declaration.constructors) {
            val constructorSymbol = constructor.symbol ?: continue
            val descriptor = descriptorOf(constructorSymbol.parameterTypes, VoidType)
            val mv = writer.visitMethod(constructorAccess(constructor), "<init>", descriptor, null, null)
            mv.visitCode()

            mv.visitVarInsn(ALOAD, 0)
            mv.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false)

            emitFieldInitializers(mv, fields, symbol)
            emitInstancePurrLockInitializer(mv, declaration)

            val scope = MethodScope(
                owner = symbol.jvmInternalName,
                mv = mv,
                lambdaIdentity = null,
                firstSlot = 1,
                static = false,
            )
            for (parameter in constructor.parameterSymbols) scope.allocate(parameter)

            for (statement in constructor.body.statements) emitStatement(statement, scope)

            mv.visitInsn(RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
    }

    /** `this.$lock = new StampedLock()`, when this class' `purr` blocks need the instance lock. */
    private fun emitInstancePurrLockInitializer(mv: MethodVisitor, declaration: ClassDeclaration) {
        if (PurrLock.INSTANCE !in locksUsedBy(declaration)) return
        mv.visitVarInsn(ALOAD, 0)
        mv.visitTypeInsn(NEW, STAMPED_LOCK_CLASS)
        mv.visitInsn(DUP)
        mv.visitMethodInsn(INVOKESPECIAL, STAMPED_LOCK_CLASS, "<init>", "()V", false)
        mv.visitFieldInsn(
            PUTFIELD,
            currentClass!!.jvmInternalName,
            purrLockFieldName(PurrLock.INSTANCE),
            "L$STAMPED_LOCK_CLASS;",
        )
    }

    private fun constructorAccess(constructor: ConstructorDeclaration): Int {
        val visibility = when {
            constructor.isPrivate -> ACC_PRIVATE
            constructor.isProtected -> ACC_PROTECTED
            else -> ACC_PUBLIC
        }
        return visibility
    }

    // ------------------------------------------------- a meow class' derived methods

    /**
     * The `equals`, `hashCode` and `toString` a `meow class` gets from its fields.
     *
     * All three are JDK 8 APIs 鈥擺java.lang.Object]'s own `equals`/`hashCode`/`toString`, `getClass`,
     * `java.util.Objects` and `java.lang.StringBuilder` 鈥攚hich is what `V1_8` program classes are
     * written against.
     *
     * A body the author wrote wins: the analyzer left the class' own `equals`/`hashCode` in
     * [ClassSymbol.functions], so emitting a derived one beside it would produce a duplicate method
     * and the JVM would reject the class file.
     */
    private fun emitDerivedObjectMethods(
        writer: ClassWriter,
        declaration: ClassDeclaration,
        symbol: ClassSymbol,
    ) {
        if (!declaresOwnMethod(symbol, "equals", listOf(ObjectType))) {
            emitDerivedEquals(writer, symbol)
        }
        if (!declaresOwnMethod(symbol, "hashCode", emptyList())) {
            emitDerivedHashCode(writer, symbol)
        }
        if (!declaresOwnMethod(symbol, "toString", emptyList())) {
            emitDerivedToString(writer, symbol)
        }
    }

    /**
     * Whether the *author* declared [name] with exactly [parameterTypes] on this class.
     *
     * `declaration != null` is what makes this the author's method rather than the analyzer's own
     * record of a derived one: the derived `equals`/`hashCode` are registered as functions too, so
     * that Neko source can call them, and a test that did not tell the two apart would find every
     * derived method already "declared" and emit nothing at all.
     *
     * Only the class' *own* functions are consulted: an inherited `equals` is overridden here by the
     * fields of this class, which is what writing `meow` asks for.
     */
    private fun declaresOwnMethod(symbol: ClassSymbol, name: String, parameterTypes: List<NekoType>): Boolean =
        symbol.functions[name].orEmpty().any {
            it.declaration != null && it.parameterTypes == parameterTypes
        }

    /**
     * `public boolean equals(java.lang.Object other)`, comparing every field.
     *
     * The three tests before the fields are the same ones every hand-written `equals` starts with,
     * and each has to be there for a reason:
     *
     *  * `this == other` answers `true` without reading a field. That is what makes `x == x` true
     *    even for a class whose fields do not satisfy `equals` themselves, and it is what makes the
     *    comparison safe when `other` is the very object being asked.
     *  * `other == null` answers `false`. `equals(null)` is a legal call, and it is the one argument
     *    for which the receiver's own fields cannot be compared with anything.
     *  * `other.getClass() != this.getClass()` answers `false`. The parameter's static type is
     *    `Object`, so `other` may be any object at all; comparing field for field would need the
     *    cast first, and a cast from an unrelated class would throw. Requiring the *exact* class also
     *    makes `equals` symmetric between a class and a subclass, which is the property the contract
     *    actually demands.
     *
     * Only then is `other` cast, which the class test has made safe, and the fields compared: two
     * `int`/`bool` fields with `IF_ICMPEQ`, and two references with `java.util.Objects.equals`, which
     * is the null-safe comparison a field holding `null` needs 鈥擭eko has no null test to write it
     * with.
     */
    private fun emitDerivedEquals(writer: ClassWriter, symbol: ClassSymbol) {
        val internalName = symbol.jvmInternalName
        val fields = instanceFieldsOf(symbol)
        val mv = writer.visitMethod(ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null)
        mv.visitCode()

        // Named for the test each one follows, so a reader can see the chain of reasons: same
        // reference, then non-null, then the same class, then the fields.
        val otherIsNotNull = Label()
        val sameClass = Label()
        val cast = Label()
        val notEqual = Label()

        // `this == other`: the two references, compared as references.
        mv.visitVarInsn(ALOAD, 0)
        mv.visitVarInsn(ALOAD, 1)
        mv.visitJumpInsn(IF_ACMPNE, otherIsNotNull)
        mv.visitInsn(ICONST_1)
        mv.visitInsn(IRETURN)

        // `other == null`.
        mv.visitLabel(otherIsNotNull)
        mv.visitVarInsn(ALOAD, 1)
        mv.visitJumpInsn(IFNONNULL, sameClass)
        mv.visitInsn(ICONST_0)
        mv.visitInsn(IRETURN)

        // The classes have to match exactly; `getClass` is final, so this compares the two classes
        // rather than whatever `getClass` a subclass might have written.
        mv.visitLabel(sameClass)
        mv.visitVarInsn(ALOAD, 0)
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false)
        mv.visitVarInsn(ALOAD, 1)
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false)
        mv.visitJumpInsn(IF_ACMPEQ, cast)
        mv.visitInsn(ICONST_0)
        mv.visitInsn(IRETURN)

        // `((Cls) other).field == field` for each field, jumping out of the whole method as soon as
        // one pair differs.
        mv.visitLabel(cast)
        mv.visitVarInsn(ALOAD, 1)
        mv.visitTypeInsn(CHECKCAST, internalName)
        mv.visitVarInsn(ASTORE, 2)

        for (field in fields) {
            mv.visitVarInsn(ALOAD, 0)
            mv.visitFieldInsn(GETFIELD, internalName, field.name, descriptorOf(field.type))
            mv.visitVarInsn(ALOAD, 2)
            mv.visitFieldInsn(GETFIELD, internalName, field.name, descriptorOf(field.type))
            // A primitive field is compared by value, and a *wide* one cannot be compared by a branch
            // instruction: `lcmp`/`fcmp`/`dcmp` first, then the test against zero. The narrow ones are
            // ints on the stack, so one branch instruction is the whole comparison.
            when {
                field.type.isWidePrimitive -> {
                    mv.visitInsn(wideCompareOpcode(field.type))
                    mv.visitJumpInsn(IFNE, notEqual)
                }

                BuiltinTypes.isPrimitive(field.type) -> mv.visitJumpInsn(IF_ICMPNE, notEqual)

                // A reference field goes through `Objects.equals`, which is what a field holding
                // `null` needs and which dispatches to the field's own `equals` otherwise.
                else -> {
                    mv.visitMethodInsn(
                        INVOKESTATIC,
                        "java/util/Objects",
                        "equals",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                        false,
                    )
                    mv.visitJumpInsn(IFEQ, notEqual)
                }
            }
        }

        mv.visitInsn(ICONST_1)
        mv.visitInsn(IRETURN)

        mv.visitLabel(notEqual)
        mv.visitInsn(ICONST_0)
        mv.visitInsn(IRETURN)

        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * `public int hashCode()`, folding every field into one number.
     *
     * `result = 31 * result + hash` is the shape Java's own generated hash codes use, and 31 is
     * chosen for the same reason it is there: it is an odd prime, so the fields are spread across the
     * `int` range rather than clustering, and `31 * x` is a shift and a subtract on any JVM.
     *
     * A field's own hash is the field for `int`/`bool` and `java.util.Objects.hashCode` otherwise 鈥?     * the same split `equals` makes, which is what keeps the two consistent: instances that compare
     * equal fold equal field values and so produce equal numbers.
     *
     * This is deliberately *not* `java.util.Objects.hash(...)`, which takes the fields as an `Object`
     * array: boxing every `int` field to hand it over would make a derived hash code allocate.
     */
    private fun emitDerivedHashCode(writer: ClassWriter, symbol: ClassSymbol) {
        val internalName = symbol.jvmInternalName
        val mv = writer.visitMethod(ACC_PUBLIC, "hashCode", "()I", null, null)
        mv.visitCode()

        mv.visitInsn(ICONST_1)
        mv.visitVarInsn(ISTORE, 1)

        for (field in instanceFieldsOf(symbol)) {
            mv.visitIntInsn(BIPUSH, 31)
            mv.visitVarInsn(ILOAD, 1)
            mv.visitInsn(IMUL)
            mv.visitVarInsn(ALOAD, 0)
            mv.visitFieldInsn(GETFIELD, internalName, field.name, descriptorOf(field.type))
            // A primitive folds in as its own `int` value, without allocating; everything else goes
            // through `Objects.hashCode`, which is what keeps the derived hash consistent with the
            // derived `equals` for a reference field. The wide primitives are the ones that need real
            // work: their first four bytes alone are not their hash, so `Long.hashCode`'s fold and
            // `Float`/`Double`'s widening have to happen here.
            when {
                field.type.isWidePrimitive -> emitWideHashOf(field.type, mv)
                BuiltinTypes.isPrimitive(field.type) -> Unit
                else -> mv.visitMethodInsn(
                    INVOKESTATIC,
                    "java/util/Objects",
                    "hashCode",
                    "(Ljava/lang/Object;)I",
                    false,
                )
            }
            mv.visitInsn(IADD)
            mv.visitVarInsn(ISTORE, 1)
        }

        mv.visitVarInsn(ILOAD, 1)
        mv.visitInsn(IRETURN)

        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** The declared instance fields of [symbol], in declaration order, skipping any unanalyzed one. */
    private fun instanceFieldsOf(symbol: ClassSymbol): List<FieldSymbol> =
        symbol.fields.values.filterNot { it.isStatic }

    /**
     * `public String toString()`, printing the class name and every field: `Cat(age=1, name="cat")`.
     *
     * The shape is the one `meow class` implies rather than one the language offers to change: the
     * simple class name, then `(` 鈥?`)` around a comma-separated `field=value` list in declaration
     * order. Nothing else about it is derivable, so it is fixed here and a hand-written `toString`
     * is the way to get anything different.
     *
     * Two choices are worth naming. A `string` field is **quoted**, which is the only reading that
     * keeps the output unambiguous 鈥?an unquoted empty string, a string that is itself `)`, and the
     * text `null` would otherwise all be indistinguishable from the fields around them 鈥?and matching
     * quotes are the convention every language with a derived `toString` follows. A `string?` field is
     * the one case where a *value* decides the format: a present one is quoted and an absent one
     * prints the bare `null`, because `"null"` would claim to be a string. Every other field prints
     * exactly as its own `toString` does, which is what makes a `meow class` holding a `meow class`
     * print the whole thing: `Cat(age=1, owner=Owner(name="kusa"))`.
     *
     * A field that is null prints `null`, because the values go through `StringBuilder.append` and
     * `append`'s reference overloads all answer `String.valueOf`, which is `"null"` for a null
     * reference. That is what keeps this method from being the one place the nullable type does not
     * cover: `toString` on a `meow class` cannot throw, whatever its fields hold.
     */
    private fun emitDerivedToString(writer: ClassWriter, symbol: ClassSymbol) {
        val internalName = symbol.jvmInternalName
        val mv = writer.visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null)
        mv.visitCode()

        // `StringBuilder` rather than repeated `String` concatenation: the fields are known only here,
        // so there is no chain of `+` to lower, and one builder is what a Java compiler emits anyway.
        mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
        mv.visitInsn(DUP)
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)

        val className = symbol.jvmInternalName.substringAfterLast('/')
        appendConstant(mv, "$className(")

        for ((index, field) in instanceFieldsOf(symbol).withIndex()) {
            if (index > 0) appendConstant(mv, ", ")

            // A `string` field is quoted, and the opening quote goes into the *label* rather than
            // being appended on its own: the value is already on the stack by then, and a constant
            // pushed afterwards would land on top of it rather than beside it.
            //
            // A nullable string is the one case where a *value* decides the format. An absent one has
            // to print the bare word `null` rather than `"null"`, because the quotes claim "this is a
            // string" and a `null` is not one 鈥?so both halves of the quoting become conditional, and
            // that needs the value somewhere other than the stack.
            val plainString = field.type === StringType
            val nullableString = field.type is NullableType && field.type.inner === StringType
            appendConstant(mv, if (plainString) "${field.name}=\"" else "${field.name}=")

            mv.visitVarInsn(ALOAD, 0)
            mv.visitFieldInsn(GETFIELD, internalName, field.name, descriptorOf(field.type))

            when {
                nullableString -> emitQuotedStringAppend(mv)
                else -> {
                    mv.visitMethodInsn(
                        INVOKEVIRTUAL,
                        "java/lang/StringBuilder",
                        "append",
                        appendDescriptorOf(field.type),
                        false,
                    )
                    if (plainString) appendConstant(mv, "\"")
                }
            }
        }

        appendConstant(mv, ")")
        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            "java/lang/StringBuilder",
            "toString",
            "()Ljava/lang/String;",
            false,
        )
        mv.visitInsn(ARETURN)

        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * Appends a nullable `String` in quotes, or the bare word `null` when it is absent.
     *
     * The value arrives on top of the builder, and quoting it means putting text on *both* sides 鈥?     * which the stack cannot do directly, because everything appended has to be pushed after what it
     * follows. So the value is put in a local for the duration. Slot 1 is free in a `toString` that
     * takes no parameters, and the method is generated so narrowly that nothing else can want it.
     */
    private fun emitQuotedStringAppend(mv: MethodVisitor) {
        val absent = Label()
        val end = Label()

        mv.visitVarInsn(ASTORE, 1)

        mv.visitVarInsn(ALOAD, 1)
        mv.visitJumpInsn(IFNULL, absent)

        appendConstant(mv, "\"")
        mv.visitVarInsn(ALOAD, 1)
        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            "java/lang/StringBuilder",
            "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
            false,
        )
        appendConstant(mv, "\"")
        mv.visitJumpInsn(GOTO, end)

        // Absent: `append(Object)` on the `null` prints `null`, unquoted.
        mv.visitLabel(absent)
        mv.visitVarInsn(ALOAD, 1)
        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            "java/lang/StringBuilder",
            "append",
            "(Ljava/lang/Object;)Ljava/lang/StringBuilder;",
            false,
        )

        mv.visitLabel(end)
    }

    /** Appends the constant string [value] to the `StringBuilder` on the stack. */
    private fun appendConstant(mv: MethodVisitor, value: String) {
        mv.visitLdcInsn(value)
        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            "java/lang/StringBuilder",
            "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
            false,
        )
    }

    /**
     * The `StringBuilder.append` overload [type] needs, so a field prints the way its own `toString`
     * does.
     *
     * `byte` and `short` have no overload of their own and are ints on the stack, so they take
     * `append(int)`; a `char` has one, because `append(int)` would print a number where
     * `Character.toString` prints the character itself. Everything else 鈥?including a nullable type,
     * which is carried boxed 鈥?takes `append(Object)`, which prints `null` rather than throwing.
     */
    private fun appendDescriptorOf(type: NekoType): String = when (type) {
        BoolType -> "(Z)Ljava/lang/StringBuilder;"
        CharType -> "(C)Ljava/lang/StringBuilder;"
        LongType -> "(J)Ljava/lang/StringBuilder;"
        FloatType -> "(F)Ljava/lang/StringBuilder;"
        DoubleType -> "(D)Ljava/lang/StringBuilder;"
        ByteType, ShortType, IntType -> "(I)Ljava/lang/StringBuilder;"
        else -> "(Ljava/lang/Object;)Ljava/lang/StringBuilder;"
    }

    /** `<init>`'s middle step: every non-static field's initializer, in declaration order. */
    private fun emitFieldInitializers(
        mv: MethodVisitor,
        fields: List<FieldDeclaration>,
        symbol: ClassSymbol,
    ) {
        val scope = MethodScope(
            owner = symbol.jvmInternalName,
            mv = mv,
            lambdaIdentity = null,
            firstSlot = 1,
            static = false,
        )

        for (field in fields) {
            val fieldSymbol = field.symbol ?: continue
            val initializer = field.initializer ?: continue
            markLine(mv, initializer)
            // The receiver sits *under* the value for `putfield`, so it is pushed first 鈥?before the
            // conversion, which would otherwise have a receiver in the way of a boxing `invokestatic`.
            mv.visitVarInsn(ALOAD, 0)
            emitExpression(initializer, scope)
            emitStoreValue(initializer.type, fieldSymbol.type, mv)
            mv.visitFieldInsn(
                PUTFIELD,
                symbol.jvmInternalName,
                fieldSymbol.name,
                descriptorOf(fieldSymbol.type),
            )
        }
    }

    // --------------------------------------------------------------- a method

    /**
     * `public static void main(String[])`, for a program whose `main` takes no arguments.
     *
     * The JVM launches a class by looking for exactly `main(String[])`, so a program that writes
     * `fun main()` 鈥攖he common case, since the arguments are rarely read and this language has no
     * array literal to build them with 鈥攚ould otherwise compile and then have no entry point at
     * all. Rather than insist on a parameter nobody uses, a bridge is emitted that calls the
     * declared function.
     *
     * The bridge is only for a `static`, argument-less, value-less `main`, which is the shape that
     * means "start here". A non-static `main` is an ordinary instance member and cannot be an entry
     * point, and one that returns a value is not the shape the launcher calls. A `main` that already
     * takes `string[]` is the entry point itself and needs no bridge 鈥攅mitting one would collide
     * with it.
     */
    private fun emitMainBridge(
        writer: ClassWriter,
        declaration: ClassDeclaration,
        symbol: ClassSymbol,
    ) {
        if (declaration.isInterface) return

        val alreadyAnEntryPoint = declaration.functions.any { function ->
            function.name == "main" &&
                function.parameters.size == 1 &&
                function.symbol?.isStatic == true
        }
        if (alreadyAnEntryPoint) return

        val bridged = declaration.functions.firstOrNull { function ->
            val functionSymbol = function.symbol ?: return@firstOrNull false
            function.name == "main" &&
                function.parameters.isEmpty() &&
                functionSymbol.isStatic &&
                functionSymbol.returnType == VoidType
        } ?: return

        val mv = writer.visitMethod(
            ACC_PUBLIC or ACC_STATIC,
            "main",
            "([Ljava/lang/String;)V",
            null,
            null,
        )
        mv.visitCode()
        mv.visitMethodInsn(INVOKESTATIC, symbol.jvmInternalName, "main", "()V", false)
        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitFunction(
        writer: ClassWriter,
        function: FunctionDeclaration,
        symbol: FunctionSymbol,
        internalName: String,
    ) {
        val returnType = symbol.returnType
        val descriptor = descriptorOf(symbol.parameterTypes, returnType)
        // `main` is a JVM entry point only when the source says `static`; nothing is added on the
        // author's behalf, so a program without a static `main` simply has no entry point.
        var access = ACC_PUBLIC or if (symbol.isStatic) ACC_STATIC else 0
        if (function.isPrivate) access = (access and ACC_PUBLIC.inv()) or ACC_PRIVATE

        // An abstract function 鈥攐ne written in an interface with no body 鈥攊s declared and never
        // emitted, because there is nothing to emit after the signature.
        if (function.isAbstract) {
            access = access or ACC_ABSTRACT
            writer.visitMethod(access, function.name, descriptor, null, null).visitEnd()
            return
        }

        // A function that declares `emit` also declares that it may `raise`, and a `raise` is
        // lowered to throwing `EarlyEmitException` 鈥攕o that exception is what the class file's
        // `Exceptions` attribute records. A body in an interface is a default method, which the JVM
        // permits from Java 8 on.
        val declaredExceptions = if (symbol.canRaise) {
            arrayOf(earlyEmitExceptionClass())
        } else {
            null
        }

        val mv = writer.visitMethod(access, function.name, descriptor, null, declaredExceptions)
        mv.visitCode()

        // Parameters occupy the first slots; an instance method's `this` occupies slot 0.
        val scope = MethodScope(
            owner = internalName,
            mv = mv,
            lambdaIdentity = null,
            firstSlot = if (symbol.isStatic) 0 else 1,
            static = symbol.isStatic,
            builderPrefix = function.name,
            returnType = returnType,
        )

        for (parameter in parametersByFunction[function].orEmpty()) {
            scope.allocate(parameter)
        }

        for (statement in function.body?.statements.orEmpty()) {
            emitStatement(statement, scope)
        }

        // A void function falls off the end; a value-returning one is guaranteed by the analyzer to
        // have returned on every path, but emitting a default keeps the class file verifiable even
        // for a tree that analysis rejected.
        emitDefaultReturn(returnType, mv)

        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    // ------------------------------------------------------------ statements

    private fun emitStatement(statement: Statement, scope: MethodScope) {
        markLine(scope.mv, statement)
        when (statement) {
            is BlockStatement -> for (inner in statement.statements) emitStatement(inner, scope)

            is VariableDeclarationStatement -> emitVariableDeclaration(statement, scope)
            is AssignmentStatement -> emitAssignment(statement, scope)

            is ExpressionStatement -> {
                emitExpression(statement.expression, scope)
                popIfValue(statement.expression.type, scope.mv)
            }

            is ReturnStatement -> emitReturn(statement, scope)
            is YieldStatement -> emitYield(statement, scope)
            is IfStatement -> emitIf(statement, scope)
            is WhileStatement -> emitWhile(statement, scope)
            is WhenStatement -> emitWhen(statement, scope)
            is RaiseStatement -> emitRaise(statement, scope)
            is TryStatement -> emitTry(statement, scope)
            is PurrStatement -> emitPurr(statement, scope)
            is IndexAssignmentStatement -> emitIndexAssignment(statement, scope)
        }
    }

    private fun emitVariableDeclaration(statement: VariableDeclarationStatement, scope: MethodScope) {
        val symbol = statement.symbol ?: return
        val initializer = statement.initializer
        if (initializer != null) {
            emitExpression(initializer, scope)
            // A `byte`, `short` or `char` local holds a value narrowed to its type, which is what lets
            // every later read of it skip the conversion; a nullable one holds a *boxed* value, which
            // is what lets `null` be stored in it at all.
            emitStoreValue(initializer.type, symbol.type, scope.mv)
        } else {
            pushDefault(symbol.type, scope.mv)
        }
        val slot = scope.allocate(symbol)
        scope.mv.visitVarInsn(storeOpcode(symbol.type), slot)
    }

    private fun emitAssignment(statement: AssignmentStatement, scope: MethodScope) {
        // `obj.field = value`: the receiver has to be on the stack *under* the value for `putfield`,
        // so it is evaluated first, before the value is emitted.
        val memberTarget = statement.target as? MemberAccessExpression
        if (memberTarget != null) {
            val field = memberTarget.resolvedField
            if (field == null) {
                // Analysis rejected this; still emit both sides so the method stays verifiable.
                emitExpression(memberTarget.receiver, scope)
                emitExpression(statement.value, scope)
                scope.mv.visitInsn(POP2)
                return
            }
            // A static field is written by class name, so no receiver is pushed; an instance one
            // needs its object on the stack *under* the value, which is why the receiver goes first.
            if (!field.isStatic) emitExpression(memberTarget.receiver, scope)
            emitExpression(statement.value, scope)
            emitStoreValue(statement.value.type, field.type, scope.mv)
            if (field.isStatic) {
                scope.mv.visitFieldInsn(
                    PUTSTATIC,
                    field.enclosingClass.jvmInternalName,
                    field.name,
                    descriptorOf(field.type),
                )
            } else {
                scope.mv.visitFieldInsn(
                    PUTFIELD,
                    field.enclosingClass.jvmInternalName,
                    field.name,
                    descriptorOf(field.type),
                )
            }
            return
        }

        emitExpression(statement.value, scope)
        val name = statement.target as? NameExpression ?: return
        val symbol = name.resolution ?: return

        when (symbol) {
            is LocalSymbol -> {
                emitStoreValue(statement.value.type, symbol.type, scope.mv)
                // A statement that introduces the name -- `instance = Instance()`, where nothing was
                // in scope before -- needs a slot of its own. One that writes to a local which
                // already exists stores into the slot that local already has, which is what makes a
                // second assignment to the same name reach the same variable.
                val slot = if (statement.declaredLocal === symbol) {
                    scope.allocate(symbol)
                } else {
                    scope.slotOf(symbol)
                }
                if (slot != null) {
                    scope.mv.visitVarInsn(storeOpcode(symbol.type), slot)
                } else {
                    // The analyzer rejects this; emit a POP so the stack stays balanced regardless.
                    scope.mv.visitInsn(popOpcode(symbol.type))
                }
            }

            is FieldSymbol -> if (symbol.isStatic) {
                emitStoreValue(statement.value.type, symbol.type, scope.mv)
                scope.mv.visitFieldInsn(
                    PUTSTATIC,
                    symbol.enclosingClass.jvmInternalName,
                    symbol.name,
                    descriptorOf(symbol.type),
                )
            } else {
                // Implicit `this.field = value`, which is the only way a bare name reaches an
                // instance field: the analyzer allows it only inside a non-static member. The
                // receiver has to sit *under* the value for `putfield`, so it is pushed after it and
                // the two are swapped 鈥?which works because the value has already been converted to
                // the field's type, and every type that can be written this way is one slot wide once
                // its name is `byte`, `short`, `char`, `int`, `bool`, a reference or a nullable type.
                // A `long` or a `double` is the exception, and `SWAP` is not defined for a two-slot
                // value: the value goes into a temporary instead, which keeps the order of evaluation
                // 鈥?the value is still read before the receiver is 鈥?with three instructions whose
                // stack effect is obvious.
                val twoSlots = BuiltinTypes.slotCountOf(symbol.type) == 2
                if (twoSlots) {
                    val scratch = scope.allocateTemp(symbol.type)
                    scope.mv.visitVarInsn(storeOpcode(symbol.type), scratch)
                    scope.mv.visitVarInsn(ALOAD, 0)
                    scope.mv.visitVarInsn(loadOpcode(symbol.type), scratch)
                } else {
                    // The conversion happens *before* the receiver is pushed, which is the whole
                    // reason it sits here rather than after the `SWAP`: boxing a `long` into a
                    // `long?` pops two slots, and with the receiver already on the stack those two
                    // would be the receiver and half the value.
                    emitStoreValue(statement.value.type, symbol.type, scope.mv)
                    scope.mv.visitVarInsn(ALOAD, 0)
                    scope.mv.visitInsn(SWAP)
                }
                scope.mv.visitFieldInsn(
                    PUTFIELD,
                    symbol.enclosingClass.jvmInternalName,
                    symbol.name,
                    descriptorOf(symbol.type),
                )
            }

            else -> scope.mv.visitInsn(popOpcode(symbol.type))
        }
    }

    private fun emitReturn(statement: ReturnStatement, scope: MethodScope) {
        val value = statement.value
        if (value == null) {
            // A `return` inside a `purr` block leaves the method without ever reaching the release
            // that follows the block, so it unlocks first 鈥?the value is already off the stack here.
            emitPurrReleaseOnExit(scope)
            scope.mv.visitInsn(RETURN)
            return
        }
        emitExpression(value, scope)
        // `return b` where `b` is a `byte` has to answer a narrowed `byte`, not the `int` an arithmetic
        // expression on them left behind, and `return 1` out of a `fun f(): int?` has to answer the
        // `Integer` the box is 鈥?the two conversions a store makes, made to the method's own type.
        val target = scope.returnType
        emitStoreValue(value.type, target, scope.mv)
        // Released *after* the value is ready and converted, so the unlock call cannot disturb what is
        // about to be returned. The converted value stays on the stack through the unlock, which is
        // exactly what a `return` from inside a `finally` does in Java bytecode.
        emitPurrReleaseOnExit(scope)
        // The opcode follows what the *method* is declared to answer, which is what the verifier
        // checks it against; a rejected body whose method has no type worth speaking of falls back to
        // the value's own, so a class file still comes out.
        val opcodeType = if (target === ErrorType || target === VoidType) value.type else target
        scope.mv.visitInsn(returnOpcode(opcodeType))
    }

    /**
     * `yield x + y` produces the value of the construct it appears in 鈥攖he lambda's, the `try`
     * expression's whose extractor it is written in, or the `nya` value form's whose block it is
     * written in.
     *
     * A lambda's `yield` returns from the generated `invoke`, and an extractor's answers the
     * interface's erased `R`, which is `Object`, so the value is boxed on the way out. A `nya`'s
     * cannot do either: its block is emitted *inline*, in the same method as the construct, so there
     * is no call to return from 鈥?it writes into the slot the two blocks share and jumps to the label
     * where the value is picked up. That is also why the value is boxed here: the slot is one `Object`
     * wide whatever the `nya` is worth.
     */
    private fun emitYield(statement: YieldStatement, scope: MethodScope) {
        emitExpression(statement.value, scope)

        val nya = scope.nyaResult
        if (nya != null) {
            emitStoreValue(statement.value.type, ObjectType, scope.mv)
            scope.mv.visitVarInsn(ASTORE, nya.slot)
            scope.mv.visitJumpInsn(GOTO, nya.end)
            return
        }

        // A `yield` in a lambda or an extractor is a `return` from the method it was generated in, so
        // it leaves a `purr` block the same way a `return` does and has to unlock first.
        if (scope.extractorResult) {
            emitBox(statement.value.type, scope.mv)
            emitPurrReleaseOnExit(scope)
            scope.mv.visitInsn(ARETURN)
            return
        }
        emitStoreValue(statement.value.type, scope.returnType, scope.mv)
        // The lambda's `invoke` answers whatever its function type says. A rejected body can leave the
        // scope without one, and then the value's own type is the only thing left to answer with.
        val target = scope.returnType
        emitPurrReleaseOnExit(scope)
        scope.mv.visitInsn(returnOpcode(if (target === VoidType || target === ErrorType) statement.value.type else target))
    }

    private fun emitIf(statement: IfStatement, scope: MethodScope) {
        val mv = scope.mv
        val elseLabel = Label()
        val endLabel = Label()

        markLine(mv, statement.condition)
        emitExpression(statement.condition, scope)
        mv.visitJumpInsn(IFEQ, elseLabel)

        emitStatement(statement.thenBranch, scope)
        if (statement.elseBranch != null) mv.visitJumpInsn(GOTO, endLabel)

        mv.visitLabel(elseLabel)
        statement.elseBranch?.let { emitStatement(it, scope) }

        if (statement.elseBranch != null) mv.visitLabel(endLabel)
    }

    private fun emitWhile(statement: WhileStatement, scope: MethodScope) {
        val mv = scope.mv
        val startLabel = Label()
        val endLabel = Label()

        mv.visitLabel(startLabel)
        markLine(mv, statement.condition)
        emitExpression(statement.condition, scope)
        mv.visitJumpInsn(IFEQ, endLabel)

        emitStatement(statement.body, scope)
        mv.visitJumpInsn(GOTO, startLabel)
        mv.visitLabel(endLabel)
    }

    /**
     * `when (subject) { value -> body; ... }`
     *
     * The subject is evaluated exactly once and kept in a temporary local, so a branch test cannot
     * re-run a side-effecting subject. Each branch body is labelled separately and jumps to the end
     * of the statement, which is what makes the arms mutually exclusive.
     */
    private fun emitWhen(statement: WhenStatement, scope: MethodScope) {
        val mv = scope.mv
        val subjectType = statement.subject.type
        val subjectSlot = scope.allocateTemp(subjectType)

        markLine(mv, statement.subject)
        emitExpression(statement.subject, scope)
        mv.visitVarInsn(storeOpcode(subjectType), subjectSlot)

        val endLabel = Label()
        val bodies = statement.branches.map { Label() }
        val elseIndex = statement.branches.indexOfFirst { it.condition == null }

        for ((index, branch) in statement.branches.withIndex()) {
            val condition = branch.condition ?: continue
            markLine(mv, condition)
            mv.visitVarInsn(loadOpcode(subjectType), subjectSlot)
            emitExpression(condition, scope)
            // A nullable subject is carried boxed while a literal branch value beside it is still an
            // `int`, so the value on top has to become a reference before the test 鈥?and only then,
            // because the subject underneath cannot be reached with the condition in the way. Nothing
            // is boxed on the subject's behalf: a nullable type is never a JVM primitive.
            if (!BuiltinTypes.isPrimitive(subjectType)) emitBox(condition.type, mv)
            // A subject and its branch values are compared with the same rule `==` uses: the narrow
            // types and `bool` in one branch instruction, and a `long`, `float` or `double` through
            // a `cmp` first, because those are too wide to compare in the jump itself.
            emitEqualityTest(scope, subjectType, condition.type, bodies[index])
        }

        if (elseIndex >= 0) {
            mv.visitJumpInsn(GOTO, bodies[elseIndex])
        } else {
            // No arm matched: fall straight through to the end of the statement.
            mv.visitJumpInsn(GOTO, endLabel)
        }

        for ((index, branch) in statement.branches.withIndex()) {
            mv.visitLabel(bodies[index])
            emitStatement(branch.body, scope)
            if (index != statement.branches.lastIndex) mv.visitJumpInsn(GOTO, endLabel)
        }

        mv.visitLabel(endLabel)
    }

    /**
     * `nya condition`, `nya condition { ... }`, `nya condition { ... } else { ... }` 鈥?runs the block
     * when the condition fails, and says what a false one means otherwise.
     *
     * A `bool` condition is the two instructions a guard already compiles to 鈥?evaluate, then branch
     * on zero 鈥?so a `nya` that passes costs exactly the comparison it names and nothing else. What
     * the branch lands on is the whole of the difference between the three forms.
     *
     * The bare form is a test and a throw and nothing else, so it is compiled the way Java's own
     * `assert` is: branch *past* the throw, and let the throw follow the branch.
     *
     * ```
     *   cond; ifne passed
     *   throw new AssertionError("cond")
     *   passed:
     * ```
     *
     * That shape is what makes a bare `nya` cost one branch on the path where the check holds. The
     * opposite nesting 鈥?branch *to* the throw and `goto` around it 鈥?needs a jump and a label to
     * spare, which is the two extra instructions a statement that is merely supposed to disappear
     * when it passes cannot afford. The message is the condition's own source text, taken from its
     * span: the compiler already has the exact expression the reader wrote, and quoting it back is
     * more accurate than a message they would have had to write twice. `AssertionError`'s `String`
     * constructor is private, so the text goes in as an `Object` 鈥?the public constructor that takes
     * one 鈥?which is also the one Java's own `assert` uses.
     *
     * The two forms with a block cannot keep that shape, because there is more than a throw and a
     * label waiting on the far side: the `else` body, the failure block and the handler all converge
     * on one point afterwards, and that point is what both ways in have to reach.
     *
     * ```
     *   cond; ifeq failure
     *   else body                              鈹?or nothing, when there is no `else`
     *   goto end
     *   failure:  failure body
     *   end:
     * ```
     *
     * When there *is* a block, the condition is evaluated inside a handler for `AssertionError`
     * covering the condition and nothing else, so a bare `nya` 鈥?this one's own or one inside a
     * function it called 鈥?lands in that block instead of unwinding the stack. That is the second way
     * into the same block, and it is why the block is written for a condition of any type: `void`
     * cannot be false, so `nya close() { ... }` can only ever mean "if close() fails". The region ends
     * at the condition, before the branch, so a `nya` in the `else` block or in the failure block
     * belongs to *that* code rather than being caught here; and both entries leave the stack empty 鈥?     * the caught error is discarded, since the block is written about the failure, not about the
     * object carrying it 鈥?which is what makes the two paths meet cleanly at one label.
     *
     * The value form is the one with something to put on the stack at `end`, and both blocks meet
     * there through **one slot** rather than through the stack: a block can end in a `yield`, but it
     * can also fall off its own end, and the two paths would then leave different stack shapes. The
     * slot is seeded with the default of the result type so that even the falling-off path leaves the
     * verifier something defined to find, and `yield` writes into it 鈥?see [emitYield] 鈥?so a block's
     * answer and the `end` label agree whatever route control took to get there.
     */
    private fun emitNyaExpression(expression: NyaExpression, scope: MethodScope) {
        val mv = scope.mv
        val failure = expression.failureBlock
        val elseBlock = expression.elseBlock
        val yieldedType = expression.yieldedType
        val conditionType = expression.condition.type

        // A `bool` condition keeps the meaning it always had, so a false answer is a failure. Any
        // other type has no false to test 鈥?which is exactly the case a block is there for, and the
        // only way into it is the throw below. A `bool?` is in that second group rather than being
        // unboxed to test it: the branch itself would throw on a null, and a `nya` that can fail
        // before it has checked anything is not the construct the reader wrote.
        val testsForFalse = conditionType == BoolType

        val failureLabel = Label()
        val endLabel = Label()

        markLine(mv, expression)

        // Only the value form needs a slot: the other two are worth nothing, so there is nothing for
        // the blocks to agree about.
        val result = if (elseBlock != null && failure != null && yieldedType != null) {
            // The slot holds an erased value from either block, so it is one `Object` wide whatever
            // the `nya` is worth 鈥?the same representation a `try` expression's result uses.
            val slot = scope.allocateTemp(ObjectType)
            pushDefault(yieldedType, mv)
            emitStoreValue(yieldedType, ObjectType, mv)
            mv.visitVarInsn(ASTORE, slot)
            slot
        } else {
            null
        }

        // The bare form is the only one that fits Java's `assert` shape, so it is emitted on its own
        // and returns here: a test, a throw, and the label the test jumps to, with no failure block
        // and no `else` body to reconcile.
        if (failure == null) {
            emitBareNya(expression, scope)
            return
        }

        // The handler is only needed when there is a block for the throw to reach. Somewhere to put
        // the exception is not: nothing in the language can name it from inside the block, so it is
        // dropped where it landed rather than given a slot the way a `try`'s extractor needs one.
        val guarded = Label()
        val guardEnd = Label()
        val handlerLabel = Label()
        mv.visitTryCatchBlock(guarded, guardEnd, handlerLabel, ASSERTION_ERROR_CLASS)
        mv.visitLabel(guarded)
        emitExpression(expression.condition, scope)
        mv.visitLabel(guardEnd)

        if (testsForFalse) {
            mv.visitJumpInsn(IFEQ, failureLabel)
        } else {
            // Nothing to test, so the condition is only watched for a throw: its answer is dropped
            // and the `else` block 鈥?the reading on which it produced one 鈥?runs unconditionally.
            popIfValue(conditionType, mv)
        }

        // The blocks are emitted while their `yield` knows which slot to fill and which label to jump
        // to. Saved and restored rather than cleared because a `nya` may be written inside another
        // one's block, and there the inner construct's `yield` is the one being written.
        val enclosingNya = scope.nyaResult
        if (result != null) scope.nyaResult = NyaResult(result, endLabel)

        if (elseBlock != null) {
            emitStatement(elseBlock, scope)
        }
        // Nothing to do when the condition held, so control goes straight past the failure. Without
        // an `else` block that is also the only path, which is why the jump is unconditional.
        mv.visitJumpInsn(GOTO, endLabel)

        // Reached only from the exception table. The error is on the stack because that is how a
        // handler is entered; dropping it here leaves the frame the same shape the branch above
        // produces, so both ways into the block arrive with an empty stack.
        mv.visitLabel(handlerLabel)
        mv.visitInsn(POP)

        mv.visitLabel(failureLabel)
        emitStatement(failure, scope)
        mv.visitJumpInsn(GOTO, endLabel)

        scope.nyaResult = enclosingNya
        mv.visitLabel(endLabel)

        if (result != null && yieldedType != null) {
            mv.visitVarInsn(ALOAD, result)
            emitUnbox(yieldedType, mv)
        }
    }

    /**
     * `nya condition` 鈥?the condition is evaluated, and the throw sits on the far side of a branch
     * that is taken when the check passes.
     *
     * ```
     *   cond; ifne passed
     *   throw new AssertionError("cond")
     *   passed:
     * ```
     *
     * This is the shape `javac` gives `assert`, and it is the shape a bare `nya` wants for the same
     * reason: the passing path is one branch and no label is reached from anywhere but the fall
     * through. Nesting it the other way 鈥?branch to the throw, `goto` past it 鈥?costs a jump and a
     * label so that nothing at all happens, which is the trap a check written throughout a program
     * cannot afford.
     *
     * Nothing is left on the stack, because the condition's `bool` is consumed by the branch. The
     * analyzer has already made sure the condition *is* a `bool`, so the branch is the whole of the
     * use it gets.
     */
    private fun emitBareNya(expression: NyaExpression, scope: MethodScope) {
        val mv = scope.mv
        val passed = Label()
        emitExpression(expression.condition, scope)
        mv.visitJumpInsn(IFNE, passed)
        emitNyaThrow(expression, mv)
        mv.visitLabel(passed)
    }

    /** `throw new AssertionError(<the condition's source text>)` for a bare `nya`. */
    private fun emitNyaThrow(expression: NyaExpression, mv: MethodVisitor) {
        mv.visitTypeInsn(NEW, ASSERTION_ERROR_CLASS)
        mv.visitInsn(DUP)
        mv.visitLdcInsn(expression.condition.span.text)
        mv.visitMethodInsn(
            INVOKESPECIAL,
            ASSERTION_ERROR_CLASS,
            "<init>",
            "(Ljava/lang/Object;)V",
            false,
        )
        mv.visitInsn(ATHROW)
    }

    /** The slot and end label a `nya` value form's blocks leave their answer in and jump to. */
    private class NyaResult(val slot: Int, val end: Label)

    /** `array[index]` 鈥攁n `*aload` after the array and the index are on the stack. */
    private fun emitIndex(expression: IndexExpression, scope: MethodScope) {
        val mv = scope.mv
        emitExpression(expression.array, scope)
        emitExpression(expression.index, scope)
        val element = (expression.array.type as? ArrayType)?.element
        mv.visitInsn(arrayLoadOpcode(element ?: ErrorType))
    }

    /** `array[index] = value` 鈥攁n `*astore` with array, index and value in that order. */
    private fun emitIndexAssignment(statement: IndexAssignmentStatement, scope: MethodScope) {
        val mv = scope.mv
        emitExpression(statement.array, scope)
        emitExpression(statement.index, scope)
        emitExpression(statement.value, scope)
        val element = (statement.array.type as? ArrayType)?.element
        // An element of a `byte[]`, `short[]` or `char[]` is narrowed on the way in, so the array
        // holds values that are really of its element type rather than the `int` they were computed as;
        // an element of a nullable element type is boxed, for the same reason a nullable local is.
        if (element != null) emitStoreValue(statement.value.type, element, mv)
        mv.visitInsn(arrayStoreOpcode(element ?: ErrorType))
    }

    /**
     * `raise 1 * multiplier, multiplier` 鈥?leaves the function early, handing the values to the
     * caller's extractor.
     *
     * The lowering is the one the sample spells out: the values are boxed into an `Object[]` by a
     * generated static builder 鈥擿test$emit$0()` in the sample 鈥攖hen wrapped in the standard
     * library's `EarlyEmitException` and thrown. Boxing is what lets one carrier type serve any
     * number of raised values, and throwing is what lets the caller's `try` catch the hand-off
     * without the callee returning.
     */
    private fun emitRaise(statement: RaiseStatement, scope: MethodScope) {
        val mv = scope.mv

        // The values are boxed by a generated static builder 鈥攖he sample's `test$emit$0()` 鈥攔ather
        // than assembled here, because each value has its own descriptor and the array must hold all
        // of them. The builder is emitted once its owner's writer is complete.
        val builder = registerBuilder(statement, scope.owner, scope.builderPrefix)

        for (value in statement.values) emitExpression(value, scope)
        mv.visitMethodInsn(
            INVOKESTATIC,
            scope.owner,
            builder,
            descriptorOf(statement.values.map { it.type }, objectArrayType()),
            false,
        )

        // One slot for the `Object[]`, and a reference 鈥?so one slot wide whatever was raised.
        val valuesSlot = scope.allocateTemp(ObjectType)
        mv.visitVarInsn(ASTORE, valuesSlot)
        emitEarlyEmitThrow(scope, valuesSlot)
    }

    /** `throw new EarlyEmitException(values)`, with the array in local [valuesSlot]. */
    private fun emitEarlyEmitThrow(scope: MethodScope, valuesSlot: Int) {
        val mv = scope.mv
        val exceptionClass = earlyEmitExceptionClass()
        mv.visitTypeInsn(NEW, exceptionClass)
        mv.visitInsn(DUP)
        mv.visitVarInsn(ALOAD, valuesSlot)
        mv.visitMethodInsn(
            INVOKESPECIAL,
            exceptionClass,
            "<init>",
            "(${objectArrayType()})V",
            false,
        )
        mv.visitInsn(ATHROW)
    }

    /**
     * Emits the static builder a `raise` needs: it boxes the values into an `Object[]` and returns
     * it.
     *
     * This exists because the values have different descriptors 鈥攐ne `int`, one `String` 鈥攕o no
     * single constructor call could assemble the array. The method is `private static` and named
     * after its function, matching the sample's `test$emit$0()`.
     */
    private fun emitBuilder(writer: ClassWriter, statement: RaiseStatement, name: String) {
        val valueTypes = statement.values.map { it.type }
        val mv = writer.visitMethod(
            ACC_PRIVATE or ACC_STATIC,
            name,
            descriptorOf(valueTypes, objectArrayType()),
            null,
            null,
        )
        mv.visitCode()

        pushInt(valueTypes.size, mv)
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object")

        // The parameters occupy slots 0..n-1 *in JVM terms*, which for a `long` or a `double` is two
        // slots rather than one: the array's slot is the total width of what came before it, not the
        // number of parameters.
        val arraySlot = valueTypes.sumOf { BuiltinTypes.slotCountOf(it) }
        mv.visitVarInsn(ASTORE, arraySlot)

        var parameterSlot = 0
        for ((index, type) in valueTypes.withIndex()) {
            mv.visitVarInsn(ALOAD, arraySlot)
            pushInt(index, mv)
            mv.visitVarInsn(loadOpcode(type), parameterSlot)
            emitBox(type, mv)
            mv.visitInsn(AASTORE)
            parameterSlot += BuiltinTypes.slotCountOf(type)
        }

        mv.visitVarInsn(ALOAD, arraySlot)
        mv.visitInsn(ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * `try test(10) { emit1, emit2 -> ... }` 鈥攃alls the function and runs the extractor if it
     * raises, discarding whatever the extractor produced.
     *
     * The lowering is a `try`/`catch` around the call, exactly as the sample describes: the call sits
     * in the protected region, and `EarlyEmitException` is caught and handed to the extractor. A call
     * that returns normally jumps past the handler, so the extractor body runs only on a `raise`.
     */
    private fun emitTry(statement: TryStatement, scope: MethodScope) {
        emitExtractorTry(statement, scope, resultSlot = null)
    }

    // ------------------------------------------------------------------- purr

    /**
     * `purr { ... }` 鈥?take a `StampedLock`, run the block, unlock on the way out.
     *
     * This cannot be a library call, and the reason is the *release*. It has to happen on every way
     * out of the block, and there are four: falling off the end, `return`, `raise`, and a thrown
     * exception. The last two are covered by a catch-all handler, since a `raise` is an `athrow` like
     * any other; a `return` is not covered by anything, which is why the release is also emitted
     * inline before the `*RETURN` instruction 鈥?see `MethodScope.purr` and [emitPurrReleaseOnExit].
     *
     * ```
     *   stamp = lock.writeLock()          // or readLock()
     *   try {
     *     ...block...
     *   } catch (any) {
     *     lock.unlock(stamp); athrow
     *   }
     *   lock.unlock(stamp)
     * ```
     *
     * The stamp is a `long` the acquisition answers and `unlock` demands, so it lives in a two-slot
     * local for the whole block. That is the other reason `StampedLock` needs the compiler: a
     * `synchronized` block would need nothing carried.
     */
    private fun emitPurr(statement: PurrStatement, scope: MethodScope) {
        val mv = scope.mv
        val lockType = statement.lock
        val lockField = purrLockFieldName(lockType)

        val stampSlot = scope.allocateTemp(LongType)

        markLine(mv, statement)
        emitPurrLockLoad(lockType, lockField, scope)
        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            STAMPED_LOCK_CLASS,
            if (statement.mode == PurrMode.READ) "readLock" else "writeLock",
            "()J",
            false,
        )
        mv.visitVarInsn(LSTORE, stampSlot)

        val guarded = Label()
        val guardEnd = Label()
        val handler = Label()
        val done = Label()

        mv.visitTryCatchBlock(guarded, guardEnd, handler, null)
        mv.visitLabel(guarded)

        // A `return` inside the block leaves through the method, past both the handler and the
        // release below, so it unlocks for itself. The enclosing value is saved because a `purr` may
        // be nested in another `purr` on a different lock, and then the *inner* one's release is the
        // one that belongs to a `return` written in the inner block.
        val enclosingPurr = scope.purr.size
        scope.purr.add(PurrScope(lockType, lockField, stampSlot))
        emitStatement(statement.body, scope)
        while (scope.purr.size > enclosingPurr) scope.purr.removeLast()

        mv.visitLabel(guardEnd)
        emitPurrUnlock(lockType, lockField, stampSlot, scope)
        mv.visitJumpInsn(GOTO, done)

        // Entered with the throwable on the stack, which has to be put somewhere for the unlock call
        // to happen at all; it is then rethrown so the exception is not swallowed here. A `purr`
        // releases a lock, it does not answer a failure 鈥?that is `nya`'s job.
        mv.visitLabel(handler)
        val thrownSlot = scope.allocateTemp(ThrowableType)
        mv.visitVarInsn(ASTORE, thrownSlot)
        emitPurrUnlock(lockType, lockField, stampSlot, scope)
        mv.visitVarInsn(ALOAD, thrownSlot)
        mv.visitInsn(ATHROW)

        mv.visitLabel(done)
    }

    /**
     * The unlock emitted immediately before a `return`, so that leaving the method does not keep the
     * lock held.
     *
     * This is the one path a catch-all handler cannot cover: `return` is not a throw. `javac` copies
     * its `finally` block in front of every `return` for the same reason, and doing it here is what
     * makes `purr { return x }` safe rather than a permanently held lock.
     *
     * Every open block is released, innermost first, because a `return` leaves all of them at once.
     */
    private fun emitPurrReleaseOnExit(scope: MethodScope) {
        for (purr in scope.purr.asReversed()) {
            emitPurrUnlock(purr.lockType, purr.lockField, purr.stampSlot, scope)
        }
    }

    /** `lock.unlock(stamp)` 鈥?the receiver read from its field, the stamp from its local. */
    private fun emitPurrUnlock(
        lockType: PurrLock,
        lockField: String,
        stampSlot: Int,
        scope: MethodScope,
    ) {
        val mv = scope.mv
        emitPurrLockLoad(lockType, lockField, scope)
        mv.visitVarInsn(LLOAD, stampSlot)
        mv.visitMethodInsn(INVOKEVIRTUAL, STAMPED_LOCK_CLASS, "unlock", "(J)V", false)
    }

    /**
     * Pushes the `StampedLock` a block holds: `this.<field>` for an instance lock, and the `static`
     * field itself for a class lock, which needs no receiver.
     */
    private fun emitPurrLockLoad(lockType: PurrLock, lockField: String, scope: MethodScope) {
        val mv = scope.mv
        val descriptor = "L$STAMPED_LOCK_CLASS;"
        if (lockType == PurrLock.CLASS) {
            mv.visitFieldInsn(GETSTATIC, scope.owner, lockField, descriptor)
        } else {
            mv.visitVarInsn(ALOAD, 0)
            mv.visitFieldInsn(GETFIELD, scope.owner, lockField, descriptor)
        }
    }

    /**
     * `r: int = try f() { a -> yield 1 }` 鈥攖he same catching construct, but worth a value.
     *
     * Both paths meet at one slot: the call's own return value when it returns, and `doEmit`'s answer
     * 鈥攖he value the extractor's `yield` produced 鈥攚hen it `raise`s instead. Which is why the
     * extractor has to yield for this form to mean anything: without one, `doEmit` could only answer
     * `null`, and the reader would silently get nothing. The analyzer requires the yield; here the
     * slot is simply filled from whichever path was taken and unboxed into the `try`'s type.
     */
    private fun emitTryExpression(expression: TryExpression, scope: MethodScope) {
        val mv = scope.mv
        // The slot holds a boxed value from either path, so it is one `Object` wide whatever the
        // `try` is worth.
        val resultSlot = scope.allocateTemp(ObjectType)
        emitExtractorTry(expression, scope, resultSlot)

        mv.visitVarInsn(ALOAD, resultSlot)
        val type = expression.type
        if (type == VoidType || type === ErrorType) {
            // Analysis rejected this; a `null` of the erased type keeps the consumer verifiable.
            mv.visitInsn(POP)
            mv.visitInsn(ACONST_NULL)
        } else {
            emitUnbox(type, mv)
        }
    }

    /**
     * The `try`/`catch` both forms share.
     *
     * [resultSlot] is the local that collects the construct's value 鈥攖he call's return value on the
     * normal path, `doEmit`'s answer on the `raise` path 鈥攐r `null` when the value is discarded.
     */
    private fun emitExtractorTry(construct: ExtractorTry, scope: MethodScope, resultSlot: Int?) {
        val mv = scope.mv
        val exceptionClass = earlyEmitExceptionClass()

        val start = Label()
        val end = Label()
        val handler = Label()
        val after = Label()

        // The exception object is kept so its array can be handed to the extractor in a moment.
        val exceptionSlot = scope.allocateTemp(ThrowableType)

        mv.visitTryCatchBlock(start, end, handler, exceptionClass)

        mv.visitLabel(start)
        emitExpression(construct.call, scope)
        if (resultSlot == null) {
            popIfValue(construct.call.type, mv)
        } else {
            // The slot holds an `Object`, so a value from either path can land in it.
            if (construct.call.type != VoidType && construct.call.type !== ErrorType) {
                emitBox(construct.call.type, mv)
            } else {
                mv.visitInsn(ACONST_NULL)
            }
            mv.visitVarInsn(ASTORE, resultSlot)
        }
        mv.visitLabel(end)
        mv.visitJumpInsn(GOTO, after)

        mv.visitLabel(handler)
        mv.visitVarInsn(ASTORE, exceptionSlot)
        markLine(mv, construct.extractorBody)

        // `exception.doEmit(extractor)`, so the exception goes on the stack first. It answers an
        // `Object`: the value a yielding extractor produced, or `null` when it produced none.
        mv.visitVarInsn(ALOAD, exceptionSlot)
        emitExtractorInstance(construct, scope)
        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            exceptionClass,
            "doEmit",
            "(L${emitExtractorBaseClass()};)Ljava/lang/Object;",
            false,
        )
        if (resultSlot == null) mv.visitInsn(POP) else mv.visitVarInsn(ASTORE, resultSlot)
        mv.visitLabel(after)
    }

    /**
     * Pushes a fresh instance of the extractor literal's class.
     *
     * Like a lambda, the literal becomes a `final` class implementing the standard library's
     * `EmitExtractorN` for its arity 鈥攐r `EmitExtractorWithResultN` when the body yields 鈥攚ith
     * whatever it reads from the enclosing scope copied in through the constructor.
     */
    private fun emitExtractorInstance(construct: ExtractorTry, scope: MethodScope) {
        val mv = scope.mv
        val (captures, extractorClassName) = emitExtractorClass(construct, scope)

        mv.visitTypeInsn(NEW, extractorClassName)
        mv.visitInsn(DUP)
        for ((symbol, _) in captures) {
            val slot = scope.slotOf(symbol)
            if (slot != null) {
                mv.visitVarInsn(loadOpcode(symbol.type), slot)
            } else {
                mv.visitVarInsn(ALOAD, 0)
                mv.visitFieldInsn(
                    GETFIELD,
                    scope.owner,
                    scope.captureOf(symbol)!!,
                    descriptorOf(symbol.type),
                )
            }
        }
        mv.visitMethodInsn(
            INVOKESPECIAL,
            extractorClassName,
            "<init>",
            descriptorOf(captures.map { it.first.type }, VoidType),
            false,
        )
    }

    /**
     * Emits the class behind a `try` extractor and returns its capture fields.
     *
     * Which family the class implements is decided here, by whether the body yields: a body that
     * produces a value implements `EmitExtractorWithResultN`, whose `invoke` answers it, and one that
     * does not implements `EmitExtractorN`, whose `invoke` answers nothing. Either way the standard
     * library supplies `extract(Object[])` as a default method 鈥攊t unpacks the array and calls
     * `invoke` 鈥攕o only `invoke` is generated here. Since the interface erases its type parameters
     * to `Object`, `invoke` receives `Object`s and unboxes each into the typed local the body reads.
     */
    private fun emitExtractorClass(
        construct: ExtractorTry,
        enclosingScope: MethodScope,
    ): Pair<List<Pair<LocalSymbol, String>>, String> {
        val parameters = construct.extractorParameters
        val arity = parameters.size
        // A `try` whose extractor yields is worth a value, so it needs the interface whose `invoke`
        // returns one; everything else uses the void family.
        val yields = construct.yieldedType != null
        val interfaceName =
            if (yields) emitExtractorWithResultInterface(arity) else emitExtractorInterface(arity)
        val className = "${currentOwner}\$Emit\$${lambdaCounter++}"
        val identity = construct

        // Whatever the body reads from the enclosing method has to travel in as a field, exactly as
        // a lambda captures. The extractor's own parameters are locals of the generated `invoke`, so
        // they are in scope there and must not also be captured.
        val referenced = LinkedHashSet<LocalSymbol>()
        collectLocalReferences(construct.extractorBody, referenced)
        val captures = mutableListOf<Pair<LocalSymbol, String>>()
        for (symbol in referenced) {
            if (parameters.any { it.symbol === symbol }) continue
            if (enclosingScope.slotOf(symbol) == null && enclosingScope.captureOf(symbol) == null) continue
            captures += symbol to "captured\$${captures.size}"
        }

        val enclosingOwner = currentOwner
        currentOwner = className

        val writer = NekoClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(
            V1_8,
            ACC_FINAL or ACC_SUPER,
            className,
            null,
            "java/lang/Object",
            arrayOf(interfaceName),
        )
        writer.visitSource(currentFile.sourceName(), null)

        for ((symbol, fieldName) in captures) {
            writer.visitField(
                ACC_PRIVATE or ACC_FINAL,
                fieldName,
                descriptorOf(symbol.type),
                null,
                null,
            ).visitEnd()
        }

        emitLambdaConstructor(writer, className, captures)

        val mv = writer.visitMethod(
            ACC_PUBLIC,
            "invoke",
            // The interface erases its type parameters, so every value arrives as an `Object` 鈥攁nd a
            // yielding one answers an `Object` too.
            "(" + "Ljava/lang/Object;".repeat(arity) + ")" +
                if (yields) "Ljava/lang/Object;" else "V",
            null,
            null,
        )
        mv.visitCode()

        val scope = MethodScope(
            owner = className,
            mv = mv,
            lambdaIdentity = identity,
            firstSlot = 1,
            captures = captures.associate { it.first to it.second },
            static = false,
            builderPrefix = "invoke",
            extractorResult = yields,
        )

        // Each `Object` parameter is unboxed into a typed local the body reads.
        //
        // The *interface* passes one `Object` per parameter, and those are the first slots 鈥?`this`,
        // then `arity` references. The *locals* the body reads are typed, so a `long` or a `double`
        // among them needs two slots, and they are therefore allocated after that block rather than
        // over it: unboxing a `long` into the parameter slots would put its second half on top of the
        // next incoming `Object`, and the verifier rejects the method outright.
        val firstLocal = 1 + parameters.size
        var nextSlot = firstLocal
        parameters.forEachIndexed { index, parameter ->
            val symbol = parameter.symbol ?: return@forEachIndexed
            mv.visitVarInsn(ALOAD, index + 1)
            emitUnbox(parameter.resolvedType ?: ErrorType, mv)
            mv.visitVarInsn(storeOpcode(symbol.type), nextSlot)
            scope.reserve(symbol, nextSlot)
            nextSlot += BuiltinTypes.slotCountOf(symbol.type)
        }
        // Whatever the body allocates next must not land inside the block just written, so the scope
        // is told how far it now reaches 鈥?a `long` parameter extends it by two rather than one.
        scope.advancePast(nextSlot)

        for (inner in construct.extractorBody.statements) {
            emitStatement(inner, scope)
        }
        // A body whose `yield` is inside a branch can reach here; the interface has to answer
        // something, and `null` is the value a missing `yield` already means.
        if (yields) mv.visitInsn(ACONST_NULL)
        mv.visitInsn(RETURN)

        mv.visitMaxs(0, 0)
        mv.visitEnd()

        // A `raise` written inside the extractor body was compiled into *this* class, so its builder
        // belongs here too and must be written before the writer closes.
        drainBuilders(writer, className)

        writer.visitEnd()

        output[className] = writer.toByteArray()
        currentOwner = enclosingOwner

        return captures to className
    }

    /**
     * Boxes a primitive already on the stack into its Java reference type; reference types are left
     * alone.
     *
     * Every primitive gets a `valueOf`, including `byte`, `short` and `char`, whose wrappers have had
     * one since Java 5: a `raise` that carried a `short` has to hand the extractor an object, and the
     * extractor casts back to whatever the `emit` clause declared.
     */
    private fun emitBox(type: NekoType, mv: MethodVisitor) {
        if (!BuiltinTypes.isPrimitive(type)) return
        val boxed = BuiltinTypes.boxedInternalNameOf(type) ?: return
        mv.visitMethodInsn(
            INVOKESTATIC,
            boxed,
            "valueOf",
            "(${descriptorOf(type)})L$boxed;",
            false,
        )
    }

    /**
     * Unboxes an `Object` on the stack into [type]; reference types are left alone.
     *
     * The checkcast is what makes the cast safe rather than hopeful: the value arriving here came from
     * a `raise` or an erased type parameter, so its static type is `Object` and only the `emit`
     * clause's declared type says what it really is. A `ClassCastException` from a mismatch is the
     * right answer 鈥?the alternative is a silently wrong value.
     */
    private fun emitUnbox(type: NekoType, mv: MethodVisitor) {
        when (type) {
            ByteType -> unboxTo(mv, "java/lang/Byte", "byteValue", "()B")
            ShortType -> unboxTo(mv, "java/lang/Short", "shortValue", "()S")
            CharType -> unboxTo(mv, "java/lang/Character", "charValue", "()C")
            IntType -> unboxTo(mv, "java/lang/Integer", "intValue", "()I")
            LongType -> unboxTo(mv, "java/lang/Long", "longValue", "()J")
            FloatType -> unboxTo(mv, "java/lang/Float", "floatValue", "()F")
            DoubleType -> unboxTo(mv, "java/lang/Double", "doubleValue", "()D")
            BoolType -> unboxTo(mv, "java/lang/Boolean", "booleanValue", "()Z")
            StringType -> mv.visitTypeInsn(CHECKCAST, "java/lang/String")
            else -> Unit
        }
    }

    /** The `checkcast`-then-`xxxValue` pair every primitive unboxing is. */
    private fun unboxTo(mv: MethodVisitor, boxed: String, method: String, descriptor: String) {
        mv.visitTypeInsn(CHECKCAST, boxed)
        mv.visitMethodInsn(INVOKEVIRTUAL, boxed, method, descriptor, false)
    }

    /**
     * Narrows the value on the stack to [to] when that is a type the JVM computes on more widely than
     * it stores.
     *
     * `byte`, `short` and `char` are *not* distinct types to the JVM's instruction set: arithmetic on
     * them is `int` arithmetic 鈥?there is no `BADD` 鈥?so `b + b` leaves an `int` on the stack and only
     * the `istore` that follows makes it a `byte` again. Left alone, `100 + 100` would print `200`
     * for a type whose every value is between -128 and 127. The explicit `I2B`/`I2S`/`I2C` is what
     * makes the wrap happen where the language says the type applies.
     *
     * Only the *target* type decides this, which is why it is the only parameter: the value on the
     * stack is an `int` whatever the source type was, and a target that is not one of the three needs
     * nothing done to it.
     *
     * Every *storage point* does this 鈥?a declared local, an assignment, a field, an array element and
     * a `return` 鈥?so a value of one of these types is always in range wherever it is kept. That is
     * also what keeps the check cheap: a value read back out of a local or a field is already
     * narrowed, so the loads need no conversion of their own.
     */
    private fun emitNarrow(to: NekoType, mv: MethodVisitor) {
        when (to) {
            ByteType -> mv.visitInsn(I2B)
            ShortType -> mv.visitInsn(I2S)
            CharType -> mv.visitInsn(I2C)
            else -> Unit
        }
    }

    /**
     * Converts the value of [valueType] on the stack into the representation [targetType] is stored
     * in, which is the pair of steps every store needs.
     *
     * A value only ever arrives here as the *primitive* its expression resolved to, so the two things
     * that can be missing are both about the target:
     *
     *  * a `byte`, `short` or `char` target needs the value narrowed 鈥?see [emitNarrow], which is what
     *    makes the wrap happen where the language says the type applies;
     *  * a **nullable** target needs the value boxed, because `int?` is carried as a
     *    `java.lang.Integer` and the `int` on the stack is not one. This is the case the type checker
     *    makes easy to forget: `x: int? = 1` reads the `1` as an `int` 鈥?the nullable type has no
     *    literal of its own 鈥?and is still a legal assignment, so the conversion belongs here rather
     *    than in a rule about which expressions may be assigned where.
     *
     * Equal types need neither, and the equality test is what keeps a reference value from being
     * narrowed or a plain `int` from being boxed: `descriptorOf` already agrees with the value's own
     * type in that case, so touching the stack at all would be the mistake.
     */
    private fun emitStoreValue(valueType: NekoType, targetType: NekoType, mv: MethodVisitor) {
        if (valueType === ErrorType || targetType === ErrorType) return

        val target = targetType as? NullableType
        val stored = target?.inner ?: targetType

        // Only a primitive value ever needs converting. A reference is already what it is: `null` is
        // the absent value of every nullable reference type, and no conversion could make a `String`
        // more of a `String`.
        if (valueType is NullableType || !BuiltinTypes.isPrimitive(valueType)) return

        // A `byte`, `short` or `char` storage holds its value narrowed, whether or not it is nullable.
        emitNarrow(stored, mv)

        // A reference storage holds the value boxed: `Object` because that is what the declared type
        // is, and a nullable primitive because the box *is* how `null` is stored in it.
        if (stored === ObjectType || target != null) emitBox(valueType, mv)
    }

    /**
     * Converts a read the analyzer *narrowed* from [declared] to [actual] into the representation
     * [actual] promises.
     *
     * A nullable primitive is stored boxed 鈥?that is how `null` fits in an `int?` 鈥?so a read that
     * has been narrowed to the primitive itself arrives as an `Integer` and has to be unboxed
     * before it can be used as one. The read still loads through the declared descriptor, because
     * that is what the field or slot actually holds; this is the step that turns it back into the
     * value the narrowed type says it is.
     *
     * A nullable *reference* type needs nothing: `string?` and `string` are the same `String` on the
     * stack. That is why this asks whether the inner type is a primitive rather than whether the
     * declared type was nullable at all.
     */
    private fun emitNarrowedRead(declared: NekoType, actual: NekoType, mv: MethodVisitor) {
        if (declared !is NullableType || actual is NullableType) return
        if (!BuiltinTypes.isPrimitive(declared.inner)) return
        emitUnbox(declared.inner, mv)
    }

    // ----------------------------------------------------------- expressions

    /** Emits [expression], leaving exactly one value on the stack unless its type is void. */
    private fun emitExpression(expression: Expression, scope: MethodScope) {
        when (expression) {
            is IntegerLiteral -> emitIntegerLiteral(expression, scope.mv)
            is DoubleLiteral -> if (expression.isFloat) {
                pushFloat(expression.value.toFloat(), scope.mv)
            } else {
                pushDouble(expression.value, scope.mv)
            }

            is CharLiteral -> pushInt(expression.value.code, scope.mv)
            is StringLiteral -> scope.mv.visitLdcInsn(expression.value)
            is BoolLiteral -> scope.mv.visitInsn(if (expression.value) ICONST_1 else ICONST_0)
            // `null` is the `aconst_null` the JVM has for it, whatever the nullable type it is being
            // read as: an `int?` is carried as an `Integer`, so the very same `null` reference is the
            // absent value of every nullable type there is.
            is NullLiteral -> scope.mv.visitInsn(ACONST_NULL)

            is NameExpression -> emitName(expression, scope)
            is ThisExpression -> scope.mv.visitVarInsn(ALOAD, 0)
            is BinaryExpression -> emitBinary(expression, scope)
            is ChainedComparisonExpression -> emitChainedComparison(expression, scope)
            is UnaryExpression -> emitUnary(expression, scope)
            is CallExpression -> emitCall(expression, scope)
            is LambdaExpression -> emitLambda(expression, scope)
            is MemberAccessExpression -> emitMemberAccess(expression, scope)
            is IndexExpression -> emitIndex(expression, scope)
            is NyaExpression -> emitNyaExpression(expression, scope)
            is TryExpression -> emitTryExpression(expression, scope)
        }
    }

    /**
     * Emits a whole-number literal as the type the analyzer gave it.
     *
     * The literal's *text* is not what decides this: `1` used as a `long` is the same digits as `1`
     * used as an `int`, and the difference is exactly the context the analyzer recorded. Reading the
     * type back off the node is therefore the only correct source, and it is why the node keeps it.
     *
     * A literal analysis rejected still has to produce something, and the `int` reading is the one it
     * would have had without a context, so a class file still comes out for a program that failed to
     * compile.
     */
    private fun emitIntegerLiteral(expression: IntegerLiteral, mv: MethodVisitor) {
        when (expression.type) {
            LongType -> pushLong(expression.signedValue, mv)
            FloatType -> pushFloat(expression.signedValue.toFloat(), mv)
            DoubleType -> pushDouble(expression.signedValue.toDouble(), mv)
            // `byte`, `short`, `char`, `int` and `bool` are all one slot of `int` width on the stack;
            // the narrowing to the declared type happens where the value is stored, loaded from an
            // array, or written to a field.
            else -> pushInt(expression.intValue, mv)
        }
    }

    /**
     * Emits `args` and converts each one to the type its parameter declares.
     *
     * A parameter of `Object` accepts a value of any type 鈥?that is what a type parameter erases to 鈥?     * and a primitive handed to one has to be boxed. Doing it from [parameterTypes] rather than from
     * the argument's own type is what makes it correct for every kind of call: the callee's signature
     * is what the JVM will check the stack against.
     *
     * A mismatch between an argument's type and its parameter's is left alone, because analysis has
     * already reported it and emitting a conversion the program did not ask for would only move the
     * failure to the verifier.
     */
    private fun emitArguments(
        arguments: List<Expression>,
        parameterTypes: List<NekoType>,
        scope: MethodScope,
    ) {
        arguments.forEachIndexed { index, argument ->
            emitExpression(argument, scope)
            val parameterType = parameterTypes.getOrNull(index) ?: return@forEachIndexed
            if (argument.type == parameterType || argument.type === ErrorType) return@forEachIndexed
            emitCoerce(argument.type, parameterType, scope)
        }
    }

    /**
     * `obj.field` 鈥攔eads a field through its receiver, or reads a `static` field by class name.
     *
     * An instance field is read with `getfield`, so the receiver must be on the stack first; a static
     * one is read with `getstatic` and the receiver is evaluated only for its side effects.
     */
    private fun emitMemberAccess(expression: MemberAccessExpression, scope: MethodScope) {
        val mv = scope.mv
        val field = expression.resolvedField

        if (field == null) {
            // Analysis reported it; keep the operand stack consistent for the verifier.
            emitExpression(expression.receiver, scope)
            mv.visitInsn(POP)
            mv.visitInsn(ACONST_NULL)
            return
        }

        if (field.isStatic) {
            // The receiver of a static field 鈥攖he class name 鈥攊s not a value, so nothing is pushed
            // for it; `getstatic` alone is the whole read.
            mv.visitFieldInsn(
                GETSTATIC,
                field.enclosingClass.jvmInternalName,
                field.name,
                descriptorOf(field.type),
            )
            emitNarrowedRead(field.type, expression.type, mv)
            return
        }

        if (expression.isSafeCall) {
            emitSafeReceiver(expression.receiver, scope, absent = true) {
                mv.visitFieldInsn(
                    GETFIELD,
                    field.enclosingClass.jvmInternalName,
                    field.name,
                    descriptorOf(field.type),
                )
                // The read's type is the field's made nullable, so a primitive field is carried
                // boxed 鈥?and has to be, or the two arms of the guard would leave an `int` and a
                // `null` and the verifier would reject the merge.
                emitBox(field.type, mv)
            }
            return
        }

        emitExpression(expression.receiver, scope)
        mv.visitFieldInsn(
            GETFIELD,
            field.enclosingClass.jvmInternalName,
            field.name,
            descriptorOf(field.type),
        )
        // A read of a `val` field the analyzer narrowed 鈥?`if (point.x != null) { point.x }` 鈥?has the
        // narrowed type, and the field is still stored with its declared one.
        emitNarrowedRead(field.type, expression.type, mv)
    }

    /**
     * Emits `receiver?.member` or `callee?(...)`: the receiver, a null test, and [onPresent] for the
     * case it is there.
     *
     * The three instructions before the branch are the whole of `?.`. The receiver is pushed once and
     * `dup`ed, because it is needed both for the test and for the `getfield`/`invokevirtual` that
     * follows; the copy is what the test consumes. If it is null the copy is discarded and the
     * expression's answer is pushed instead, and the jump over [onPresent] is what makes the member
     * *not* be read 鈥?a `?.` on an absent receiver must not evaluate the arguments either, which is
     * why they are emitted inside [onPresent] rather than before the guard.
     *
     * [receiver] is the expression being guarded. For `?.` that is the object whose member is read;
     * for `f?(...)` it is the function value being called, which is why this takes the expression
     * rather than a `MemberAccessExpression` 鈥?the two safe forms differ in *what* is guarded, not in
     * how, and sharing the guard is what keeps them from drifting apart.
     *
     * [absent] says whether the absent case has an answer to push. It does for every expression 鈥?     * `null`, which is the whole point of the safe call 鈥?except a call to a function that returns
     * nothing, where there is no value to be absent and the arm has to leave the stack as it found it.
     */
    private fun emitSafeReceiver(
        receiver: Expression,
        scope: MethodScope,
        absent: Boolean,
        onPresent: () -> Unit,
    ) {
        val mv = scope.mv
        val present = Label()
        val end = Label()

        emitExpression(receiver, scope)
        mv.visitInsn(DUP)
        mv.visitJumpInsn(IFNONNULL, present)
        mv.visitInsn(POP)
        if (absent) mv.visitInsn(ACONST_NULL)
        mv.visitJumpInsn(GOTO, end)

        mv.visitLabel(present)
        onPresent()
        mv.visitLabel(end)
    }

    private fun emitName(expression: NameExpression, scope: MethodScope) {
        when (val symbol = expression.resolution) {
            is LocalSymbol -> {
                val slot = scope.slotOf(symbol)
                if (slot != null) {
                    scope.mv.visitVarInsn(loadOpcode(symbol.type), slot)
                    // The slot is declared with the symbol's own type; a narrowed read says the value
                    // is there, which for a nullable primitive means it is no longer a box.
                    emitNarrowedRead(symbol.type, expression.type, scope.mv)
                    return
                }
                // Not a local of this method: the lambda must have captured it.
                val capture = scope.captureOf(symbol)
                if (capture != null) {
                    scope.mv.visitVarInsn(ALOAD, 0)
                    scope.mv.visitFieldInsn(GETFIELD, scope.owner, capture, descriptorOf(symbol.type))
                    emitNarrowedRead(symbol.type, expression.type, scope.mv)
                    return
                }
                scope.mv.visitInsn(pushDefaultOpcode(symbol.type))
            }

            is FieldSymbol -> if (symbol.isStatic) {
                scope.mv.visitFieldInsn(
                    GETSTATIC,
                    symbol.enclosingClass.jvmInternalName,
                    symbol.name,
                    descriptorOf(symbol.type),
                )
                emitNarrowedRead(symbol.type, expression.type, scope.mv)
            } else {
                // An implicit `this.field` read: the analyzer only allows a bare reference here
                // inside a non-static method, so slot 0 holds the receiver.
                scope.mv.visitVarInsn(ALOAD, 0)
                scope.mv.visitFieldInsn(
                    GETFIELD,
                    symbol.enclosingClass.jvmInternalName,
                    symbol.name,
                    descriptorOf(symbol.type),
                )
                emitNarrowedRead(symbol.type, expression.type, scope.mv)
            }

            is FunctionSymbol ->
                // A function name used as a value is meaningless; analysis reports it. Keep the
                // operand stack consistent so the rest of the method can still be verified.
                scope.mv.visitInsn(ACONST_NULL)

            else -> scope.mv.visitInsn(ACONST_NULL)
        }
    }

    private fun emitBinary(expression: BinaryExpression, scope: MethodScope) {
        val mv = scope.mv
        val operator = expression.operator

        if (operator == BinaryOperator.AND || operator == BinaryOperator.OR) {
            // Short-circuit evaluation, exactly as `&&` and `||` behave.
            val result = Label()
            val end = Label()
            emitExpression(expression.left, scope)
            if (operator == BinaryOperator.AND) {
                mv.visitJumpInsn(IFEQ, result)
            } else {
                mv.visitJumpInsn(IFNE, result)
            }
            emitExpression(expression.right, scope)
            mv.visitJumpInsn(GOTO, end)
            mv.visitLabel(result)
            mv.visitInsn(if (operator == BinaryOperator.AND) ICONST_0 else ICONST_1)
            mv.visitLabel(end)
            return
        }

        // `==` emits its own operands, because whether they are compared as primitives or as
        // references decides what has to happen to each of them on the way in.
        if (operator == BinaryOperator.EQUAL || operator == BinaryOperator.NOT_EQUAL) {
            emitEquality(expression, scope)
            return
        }

        emitExpression(expression.left, scope)
        emitExpression(expression.right, scope)

        if (operator == BinaryOperator.ADD && expression.type == StringType) {
            // String concatenation via StringBuilder keeps `"a" + "b"` independent of indy support.
            mv.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/String",
                "concat",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false,
            )
            return
        }

        // The operands' *own* type picks the instruction, not the expression's: a comparison is worth
        // a `bool` but adds two `long`s. The analyzer has already established that both operands share
        // one numeric type and that it is a numeric one, so this reads that type off the left operand.
        val operandType = expression.left.type

        when (operator) {
            BinaryOperator.ADD -> mv.visitInsn(arithmeticOpcode(IADD, LADD, FADD, DADD, operandType))
            BinaryOperator.SUBTRACT ->
                mv.visitInsn(arithmeticOpcode(ISUB, LSUB, FSUB, DSUB, operandType))

            BinaryOperator.MULTIPLY ->
                mv.visitInsn(arithmeticOpcode(IMUL, LMUL, FMUL, DMUL, operandType))

            BinaryOperator.DIVIDE ->
                mv.visitInsn(arithmeticOpcode(IDIV, LDIV, FDIV, DDIV, operandType))

            BinaryOperator.REMAINDER ->
                mv.visitInsn(arithmeticOpcode(IREM, LREM, FREM, DREM, operandType))

            BinaryOperator.EQUAL, BinaryOperator.NOT_EQUAL -> Unit // handled above
            BinaryOperator.LESS -> emitComparison(scope, BinaryOperator.LESS, operandType)
            BinaryOperator.LESS_EQUAL -> emitComparison(scope, BinaryOperator.LESS_EQUAL, operandType)
            BinaryOperator.GREATER -> emitComparison(scope, BinaryOperator.GREATER, operandType)
            BinaryOperator.GREATER_EQUAL ->
                emitComparison(scope, BinaryOperator.GREATER_EQUAL, operandType)

            BinaryOperator.AND, BinaryOperator.OR -> Unit // handled above
        }
    }

    /**
     * The instruction that applies an arithmetic operator to two values of [type].
     *
     * `byte`, `short`, `char`, `int` and `bool` share the `int` instruction because that is how the
     * JVM computes on them: a `char` sum is an `int` sum, and the value is narrowed back to the
     * declared type when it is stored. There is no promotion here, because the analyzer has already
     * required both operands to be the same type 鈥?the choice is only about *width*.
     */
    private fun arithmeticOpcode(int: Int, long: Int, float: Int, double: Int, type: NekoType): Int =
        when (type) {
            LongType -> long
            FloatType -> float
            DoubleType -> double
            else -> int
        }

    /**
     * The `cmp` that reduces two [type] values on the stack to an `int` of -1, 0 or 1.
     *
     * `cmpg` is the variant that answers 1 for `NaN`, which is the right answer for an equality test
     * because `NaN == anything` is false either way; the distinction only matters for the relational
     * operators, and [emitComparison] is where it does.
     */
    private fun wideCompareOpcode(type: NekoType): Int = when (type) {
        LongType -> LCMP
        FloatType -> FCMPG
        DoubleType -> DCMPG
        else -> error("'$type' is not a wide primitive")
    }

    /**
     * Reduces a `long`/`float`/`double` already on the stack to the `int` its class' own `hashCode`
     * answers.
     *
     * The three folds are the ones `java.lang.Long`, `Float` and `Double` document, written out rather
     * than called: calling them would box the value first, and a derived `hashCode` that allocates
     * once per field is the cost `Objects.hash` was avoided for in the first place. `long` folds its
     * two halves together, `float` is the bits of its `int` representation, and `double` first widens
     * to a `long` and folds that.
     */
    private fun emitWideHashOf(type: NekoType, mv: MethodVisitor) {
        when (type) {
            FloatType -> mv.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/Float",
                "floatToIntBits",
                "(F)I",
                false,
            )

            DoubleType -> {
                mv.visitMethodInsn(
                    INVOKESTATIC,
                    "java/lang/Double",
                    "doubleToLongBits",
                    "(D)J",
                    false,
                )
                foldLongToInt(mv)
            }

            LongType -> foldLongToInt(mv)

            else -> error("'$type' is not a wide primitive")
        }
    }

    /** `(int) (value ^ (value >>> 32))` for the `long` on the stack, which is `Long.hashCode`. */
    private fun foldLongToInt(mv: MethodVisitor) {
        mv.visitInsn(DUP2)
        pushInt(32, mv)
        mv.visitInsn(LUSHR)
        mv.visitInsn(LXOR)
        mv.visitInsn(L2I)
    }

    /**
     * A null-safe `==` or `!=` that never throws.
     *
     * The one hazard a comparison of two references has is a `null` operand reaching a method call.
     * `a.equals(b)` throws when `a` is `null`, and that is precisely the failure this must not have:
     * `x == null` and `x != null` are the only ways a Neko program can *ask* whether a nullable value
     * is absent, so failing on the absent case would make the question unaskable. Every path below is
     * therefore chosen so that neither operand is ever dereferenced.
     *
     * Which path is decided by what the two operands are, not by one of them alone. A literal `null`
     * is a `NullLiteral` whose own type is not a value type at all 鈥?it is whatever the surrounding
     * context said 鈥?so the only reliable reading is the analyser's verdict on the pair: if the two
     * types are comparable through `isAssignableTo` in one direction, then one of them is the other's
     * supertype and the comparison is a reference one, however the operands are spelled.
     *
     * The two reference paths differ only in what they can do without a `null` test. A **literal**
     * `null` is known to be `null` at compile time, so `ref == null` is an `ifnull` 鈥?one instruction,
     * no call, nothing to throw. Anything else goes through `java.util.Objects.equals`, which tests
     * both arguments for `null` before comparing them and so is safe in every direction; it is also
     * what makes `a == b` on two plain objects compare their fields rather than their identities.
     */
    private fun emitEquality(expression: BinaryExpression, scope: MethodScope) {
        val mv = scope.mv
        val left = expression.left
        val right = expression.right
        val leftType = left.type
        val rightType = right.type
        val wantsEqual = expression.operator == BinaryOperator.EQUAL

        // A comparison where either side failed to type is emitted with the operands' own default
        // shape 鈥?a reference compare 鈥?so the method stays verifiable while the diagnostics stand.
        val notComparable = leftType === ErrorType || rightType === ErrorType ||
            leftType === VoidType || rightType === VoidType

        // `x == null` / `null == x`: the literal side is null by construction, so the *other* side is
        // compared against it with one null test rather than a call.
        val nullLiteralOnLeft = left is NullLiteral
        val nullLiteralOnRight = right is NullLiteral
        if (!notComparable && (nullLiteralOnLeft || nullLiteralOnRight)) {
            val other = if (nullLiteralOnLeft) right else left
            emitExpression(other, scope)
            // `x == null` is true when `x` is null; `x != null` is true when it is not.
            emitNullTestJump(scope, if (wantsEqual) IFNULL else IFNONNULL)
            return
        }

        // A primitive comparison, which for every type the JVM has an instruction for is the same
        // shape: leave an `int` on the stack 鈥?from `lcmp`/`fcmp`/`dcmp` when the values are too wide
        // to compare with a jump 鈥?and test it against zero.
        if (!notComparable && BuiltinTypes.isPrimitive(leftType) && BuiltinTypes.isPrimitive(rightType)) {
            emitExpression(left, scope)
            emitExpression(right, scope)
            when (leftType) {
                LongType, FloatType, DoubleType -> {
                    mv.visitInsn(wideCompareOpcode(leftType))
                    emitComparisonJump(scope, if (wantsEqual) IFEQ else IFNE)
                }

                // `byte`, `short`, `char`, `int` and `bool` are ints on the stack.
                else -> emitComparisonJump(scope, if (wantsEqual) IF_ICMPEQ else IF_ICMPNE)
            }
            return
        }

        emitExpression(left, scope)
        // `x == 1` for an `int?` is the case this handles: a nullable operand is carried boxed while
        // the literal beside it is still an `int`, so the value that is not boxed yet has to be 鈥?or
        // the pair handed to `Objects.equals` would be a reference and an `int`.
        emitBox(leftType, mv)
        emitExpression(right, scope)
        emitBox(rightType, mv)

        // A nullable primitive is carried boxed, so `int? == int?` compares two `Integer`s through
        // `Objects.equals` and answers by value 鈥?which is what makes `x == 1` true for an `int?`
        // holding one, and cannot throw on the absent one.
        emitReferenceEquality(scope)

        if (!wantsEqual) {
            val trueLabel = Label()
            val endLabel = Label()
            mv.visitJumpInsn(IFEQ, trueLabel)
            mv.visitInsn(ICONST_0)
            mv.visitJumpInsn(GOTO, endLabel)
            mv.visitLabel(trueLabel)
            mv.visitInsn(ICONST_1)
            mv.visitLabel(endLabel)
        }
    }

    /**
     * `x == null` as a `bool`: an `ifnull` that leaves `1`, and the other arm leaving `0`.
     *
     * [opcode] is the branch that means "the answer is true" 鈥?`IFNULL` for `==` and `IFNONNULL` for
     * `!=` 鈥?so the two callers share the shape and differ only in which test they ask for.
     */
    private fun emitNullTestJump(scope: MethodScope, opcode: Int) {
        val mv = scope.mv
        val trueLabel = Label()
        val endLabel = Label()
        mv.visitJumpInsn(opcode, trueLabel)
        mv.visitInsn(ICONST_0)
        mv.visitJumpInsn(GOTO, endLabel)
        mv.visitLabel(trueLabel)
        mv.visitInsn(ICONST_1)
        mv.visitLabel(endLabel)
    }

    /**
     * Emits `a == b` for two *reference* values already on the stack, leaving a `bool`.
     *
     * This is `java.util.Objects.equals`, which answers `true` for two `null`s and `false` for exactly
     * one of them, without ever dereferencing either argument. Hand-rolled, that is a `null` test on
     * both sides around an `equals` call; `Objects.equals` is that same chain, written once by the JDK
     * and already used by the `equals` a `meow class` derives, so the two spellings of "are these
     * equal?" agree by construction.
     *
     * It also dispatches properly. A `meow class` compares through its derived `equals`, and so does
     * everything else through whatever `equals` its class declares 鈥?`String.equals` for two strings,
     * `Object.equals` for two classes that declare none 鈥?because `Objects.equals` calls
     * `a.equals(b)` internally, with `a` already known not to be `null`.
     *
     * Nothing here needs to know the operands' type: `Objects.equals` takes two `Object`s and every
     * reference is one, so no `checkcast` is due and no narrower owner can be named. That the type is
     * not a parameter is the point 鈥?a version of this that took one is how `a == b` on two plain
     * objects once came to emit a call to `String.equals` and fail to verify.
     */
    private fun emitReferenceEquality(scope: MethodScope) {
        scope.mv.visitMethodInsn(
            INVOKESTATIC,
            "java/util/Objects",
            "equals",
            "(Ljava/lang/Object;Ljava/lang/Object;)Z",
            false,
        )
    }

    /**
     * Branches to [target] when the two values already on the stack 鈥?a `when` subject of
     * [operandType] and one branch value of [valueType] 鈥?are equal.
     *
     * `when` needs a *test* rather than the `bool` [emitEquality] materialises, and both are built from
     * the same two steps: a `cmp` for the types too wide for a branch instruction, then one conditional
     * jump. Keeping the two side by side is what stops the spellings of "are these equal?" drifting
     * apart 鈥?a `when` over `long` that fell through to reference equality would compile and never
     * match.
     *
     * Both types decide which of the two comparisons it is, so both are passed: a branch value read in
     * a nullable subject's type is a plain `int` while the subject is a boxed one, and comparing those
     * with `IF_ICMPEQ` would be a verification error.
     */
    private fun emitEqualityTest(
        scope: MethodScope,
        operandType: NekoType,
        valueType: NekoType,
        target: Label,
    ) {
        val mv = scope.mv
        if (BuiltinTypes.isPrimitive(operandType) && BuiltinTypes.isPrimitive(valueType)) {
            when (operandType) {
                LongType, FloatType, DoubleType -> {
                    mv.visitInsn(wideCompareOpcode(operandType))
                    mv.visitJumpInsn(IFEQ, target)
                }

                else -> mv.visitJumpInsn(IF_ICMPEQ, target)
            }
            return
        }

        emitReferenceEquality(scope)
        mv.visitJumpInsn(IFNE, target)
    }

    /**
     * Emits `a op b` for two operands of [operandType] already on the stack, leaving a boolean.
     *
     * The JVM splits comparisons in two, and the split is not cosmetic. A `byte`, `short`, `char`,
     * `int` or `bool` fits in the branch instruction itself, so `IF_ICMPLT` compares and branches in
     * one step. A `long`, `float` or `double` is too wide for that: `lcmp`/`fcmp`/`dcmp` reduce the
     * pair to an `int` of -1, 0 or 1, and a second branch tests *that* against zero.
     *
     * Which of the two `fcmp`/`dcmp` variants is used is what decides the answer for `NaN`, and the
     * choice is the one Java makes: `cmpl` answers -1 when either operand is `NaN` and `cmpg` answers
     * 1, so `<` and `<=` take the `cmpg` form 鈥?`NaN` is not less than anything 鈥?while `>` and `>=`
     * take `cmpl`. Getting this backwards would make every comparison against `NaN` answer `true`,
     * which is the opposite of what IEEE 754 and every other language say.
     */
    private fun emitComparison(scope: MethodScope, operator: BinaryOperator, operandType: NekoType) {
        val mv = scope.mv
        when (operandType) {
            LongType, FloatType, DoubleType -> {
                mv.visitInsn(wideComparisonFor(operator, operandType))
                emitComparisonJump(scope, cmpResultJump(operator))
            }

            // `byte`, `short`, `char`, `int` and `bool` are all ints on the stack, so one branch
            // instruction does the whole comparison.
            else -> emitComparisonJump(scope, intComparisonJump(operator))
        }
    }

    /**
     * Emits a branch followed by a materialised `0`/`1`, turning a jump into a `bool`.
     *
     * [opcode] is the jump that is taken when the comparison *holds*: the `true` path is the fall
     * through, which is what keeps a single shape for both the one-step and the two-step comparisons.
     */
    private fun emitComparisonJump(scope: MethodScope, opcode: Int) {
        val mv = scope.mv
        val trueLabel = Label()
        val endLabel = Label()
        mv.visitJumpInsn(opcode, trueLabel)
        mv.visitInsn(ICONST_0)
        mv.visitJumpInsn(GOTO, endLabel)
        mv.visitLabel(trueLabel)
        mv.visitInsn(ICONST_1)
        mv.visitLabel(endLabel)
    }

    /** The `IF_ICMP*` that is taken when `a op b` holds, for int-shaped operands. */
    private fun intComparisonJump(operator: BinaryOperator): Int = when (operator) {
        BinaryOperator.LESS -> IF_ICMPLT
        BinaryOperator.LESS_EQUAL -> IF_ICMPLE
        BinaryOperator.GREATER -> IF_ICMPGT
        BinaryOperator.GREATER_EQUAL -> IF_ICMPGE
        else -> error("'${operator.display}' is not a relational operator")
    }

    /** The test of a `cmp` result that is taken when `a op b` holds, for wide operands. */
    private fun cmpResultJump(operator: BinaryOperator): Int = when (operator) {
        BinaryOperator.LESS -> IFLT
        BinaryOperator.LESS_EQUAL -> IFLE
        BinaryOperator.GREATER -> IFGT
        BinaryOperator.GREATER_EQUAL -> IFGE
        else -> error("'${operator.display}' is not a relational operator")
    }

    /**
     * `a > b > c`, i.e. `a > b && b > c`.
     *
     * Each operand is evaluated once into a temporary and then reused by the two comparisons that
     * involve it. Evaluating `b` twice would be observably wrong as soon as an operand is a call,
     * which is exactly why the chain has its own node instead of being desugared.
     *
     * The comparisons are combined with short-circuit `&&`: the first failure jumps straight to the
     * `false` result.
     */
    private fun emitChainedComparison(expression: ChainedComparisonExpression, scope: MethodScope) {
        val mv = scope.mv
        val operandType = expression.operands.first().type
        val slots = expression.operands.map { operand ->
            emitExpression(operand, scope)
            // A `long` or a `double` takes two slots, so the temporary is asked for by type rather
            // than by count: a bare count would hand the next one the second half of this value.
            val slot = scope.allocateTemp(operandType)
            mv.visitVarInsn(storeOpcode(operandType), slot)
            slot
        }

        val falseLabel = Label()
        val endLabel = Label()

        for (index in expression.operators.indices) {
            mv.visitVarInsn(loadOpcode(operandType), slots[index])
            mv.visitVarInsn(loadOpcode(operandType), slots[index + 1])
            // The negated test jumps to `false`, so every comparison must hold to reach `true`.
            emitNegatedComparison(scope, expression.operators[index], operandType, falseLabel)
        }

        mv.visitInsn(ICONST_1)
        mv.visitJumpInsn(GOTO, endLabel)
        mv.visitLabel(falseLabel)
        mv.visitInsn(ICONST_0)
        mv.visitLabel(endLabel)
    }

    /**
     * Branches to [target] when `a op b` does **not** hold, for the two operands already on the stack.
     *
     * The wide types need the same `cmp`-then-test split [emitComparison] makes; the narrow ones are
     * one negated branch instruction.
     */
    private fun emitNegatedComparison(
        scope: MethodScope,
        operator: BinaryOperator,
        operandType: NekoType,
        target: Label,
    ) {
        val mv = scope.mv
        when (operandType) {
            LongType, FloatType, DoubleType -> {
                mv.visitInsn(wideComparisonFor(operator, operandType))
                mv.visitJumpInsn(negatedCmpResultJump(operator), target)
            }

            else -> mv.visitJumpInsn(negatedComparisonOpcode(operator), target)
        }
    }

    /**
     * The `cmp` a comparison on [type] uses, chosen for its `NaN` answer as [emitComparison] describes.
     *
     * `long` has only one `cmp`, so the choice only exists for the two floating-point types.
     */
    private fun wideComparisonFor(operator: BinaryOperator, type: NekoType): Int = when (type) {
        LongType -> LCMP
        FloatType -> if (operator == BinaryOperator.LESS || operator == BinaryOperator.LESS_EQUAL) FCMPG else FCMPL
        DoubleType -> if (operator == BinaryOperator.LESS || operator == BinaryOperator.LESS_EQUAL) DCMPG else DCMPL
        else -> error("'$type' is not a wide primitive")
    }

    /** The opcode that jumps when `a op b` is *false*, used to short-circuit a comparison chain. */
    private fun negatedComparisonOpcode(operator: BinaryOperator): Int = when (operator) {
        BinaryOperator.LESS -> IF_ICMPGE
        BinaryOperator.LESS_EQUAL -> IF_ICMPGT
        BinaryOperator.GREATER -> IF_ICMPLE
        BinaryOperator.GREATER_EQUAL -> IF_ICMPLT
        else -> error("'${operator.display}' is not a relational operator")
    }

    /** The test of a `cmp` result that is taken when `a op b` is *false*. */
    private fun negatedCmpResultJump(operator: BinaryOperator): Int = when (operator) {
        BinaryOperator.LESS -> IFGE
        BinaryOperator.LESS_EQUAL -> IFGT
        BinaryOperator.GREATER -> IFLE
        BinaryOperator.GREATER_EQUAL -> IFLT
        else -> error("'${operator.display}' is not a relational operator")
    }

    private fun emitUnary(expression: UnaryExpression, scope: MethodScope) {
        emitExpression(expression.operand, scope)
        when (expression.operator) {
            // The operand's own type picks the negation, and the result keeps that type: `-1L` is a
            // `long` and `-1.5f` a `float`, so a negated value fits wherever an unnegated one did.
            UnaryOperator.NEGATE -> scope.mv.visitInsn(
                when (expression.operand.type) {
                    LongType -> LNEG
                    FloatType -> FNEG
                    DoubleType -> DNEG
                    else -> INEG
                },
            )

            UnaryOperator.NOT -> {
                // `!b` is `b == 0`.
                val mv = scope.mv
                val trueLabel = Label()
                val endLabel = Label()
                mv.visitJumpInsn(IFEQ, trueLabel)
                mv.visitInsn(ICONST_0)
                mv.visitJumpInsn(GOTO, endLabel)
                mv.visitLabel(trueLabel)
                mv.visitInsn(ICONST_1)
                mv.visitLabel(endLabel)
            }
        }
    }

    /**
     * Emits a call.
     *
     * Four cases, matching the analyzer's resolution:
     *
     *  1. a construction, e.g. `CustomException()` 鈫抈new` plus `<init>`;
     *  2. a member call, e.g. `obj.f(1)` 鈫抈invokevirtual` with the receiver first, or
     *     `Cls.f(1)` 鈫抈invokestatic`;
     *  3. a named function 鈫抈invokestatic` (a built-in such as `println` first loads its receiver);
     *  4. a function *value*, e.g. the second call in `lambda()(1, 2)` 鈫抈invokeinterface`.
     */
    private fun emitCall(expression: CallExpression, scope: MethodScope) {
        val mv = scope.mv
        val callee = expression.callee

        // `CustomException()` creates an object rather than calling a function.
        val constructedType = expression.constructedType
        if (constructedType != null) {
            emitConstruction(expression, constructedType, scope)
            return
        }

        // `obj.f(...)` or `Cls.f(...)`: an instance call needs its receiver on the stack before the
        // arguments; a static one is an ordinary `invokestatic`.
        val instanceMethod = expression.instanceMethod
        if (instanceMethod != null) {
            val receiver = expression.instanceReceiver
            if (receiver != null) {
                val call = {
                    emitArguments(expression.arguments, instanceMethod.parameterTypes, scope)
                    // A method declared in an interface must be dispatched with `invokeinterface`; the
                    // JVM treats using `invokevirtual` on one as a verification error.
                    val opcode = if (instanceMethod.ownerIsInterface) INVOKEINTERFACE else INVOKEVIRTUAL
                    mv.visitMethodInsn(
                        opcode,
                        instanceMethod.jvmOwner,
                        instanceMethod.name,
                        descriptorOf(instanceMethod.parameterTypes, instanceMethod.returnType),
                        instanceMethod.ownerIsInterface,
                    )
                    // A safe call's own type is the return type made nullable, so a function answering
                    // a primitive answers a boxed one here: the absent arm pushes `null`, and the two
                    // arms have to meet on the same type.
                    if (expression.type is NullableType) emitBox(instanceMethod.returnType, mv)
                }
                // `recv?.f(...)`: the receiver is tested for null, and the call 鈥?arguments included 鈥?                // happens only when it is there. The call's own type is nullable, so an absent
                // receiver answers `null`; a function that returns nothing has no such value, and the
                // guard leaves the stack empty instead.
                if (callee is MemberAccessExpression && callee.isSafeCall) {
                    emitSafeReceiver(
                        callee.receiver,
                        scope,
                        absent = instanceMethod.returnType !== VoidType,
                        onPresent = call,
                    )
                } else {
                    emitExpression(receiver, scope)
                    call()
                }
            } else {
                emitArguments(expression.arguments, instanceMethod.parameterTypes, scope)
                mv.visitMethodInsn(
                    INVOKESTATIC,
                    instanceMethod.jvmOwner,
                    instanceMethod.name,
                    descriptorOf(instanceMethod.parameterTypes, instanceMethod.returnType),
                    false,
                )
            }
            return
        }

        if (callee is NameExpression) {
            val resolved = callee.resolution
            if (resolved is FunctionSymbol) {
                emitNamedCall(expression, resolved, scope)
                return
            }
        }

        // Calling a value of function type: push the callee, then the arguments.
        val calleeType = expression.callee.type

        if (calleeType is FunctionType || (calleeType is NullableType && calleeType.inner is FunctionType)) {
            // The signature lives under the `?`, if there is one: a nullable function value is still
            // called with the interface its own type names.
            val functionType = calleeType.unwrapNullable() as FunctionType
            val call = {
                // A function *value*'s signature is its own type, so that is what the arguments are
                // converted against 鈥?there is no symbol to ask.
                emitArguments(expression.arguments, functionType.parameters, scope)
                mv.visitMethodInsn(
                    INVOKEINTERFACE,
                    functionInterfaceName(functionType),
                    INVOKE_METHOD_NAME,
                    descriptorOf(functionType.parameters, functionType.returnType),
                    true,
                )
                // A safe call answers `null` when the callee was absent, so a function answering a
                // primitive is carried boxed here 鈥?the two arms have to meet on the same type.
                if (expression.type is NullableType) emitBox(functionType.returnType, mv)
            }

            // `f?(...)`: the callee is tested for null, and the call 鈥?arguments included 鈥?happens
            // only when it is there. There is no receiver to guard, so the guard is on the callee.
            if (expression.isSafeCall) {
                emitSafeReceiver(
                    expression.callee,
                    scope,
                    absent = functionType.returnType !== VoidType,
                    onPresent = call,
                )
            } else {
                emitExpression(callee, scope)
                call()
            }
            return
        }

        // Analysis already reported that this is not callable.
        emitExpression(callee, scope)
        emitArguments(expression.arguments, emptyList(), scope)
        popIfValue(calleeType, mv)
    }

    /**
     * Emits `new T(...)` for a construction.
     *
     * The descriptor is the one the resolved constructor declared, not the one the argument types
     * happen to imply: a constructor parameter declared `Object` accepts an `int` argument, which
     * has to be boxed on the way in. A class with no declared constructor 鈥攐ne from outside the
     * program, say 鈥攆alls back to the arguments' own types, which is what the JVM will require.
     */
    private fun emitConstruction(
        expression: CallExpression,
        type: ClassType,
        scope: MethodScope,
    ) {
        val mv = scope.mv
        val constructor = expression.resolvedConstructor
        mv.visitTypeInsn(NEW, type.internalName)
        mv.visitInsn(DUP)

        if (constructor != null) {
            expression.arguments.forEachIndexed { index, argument ->
                emitExpression(argument, scope)
                emitCoerce(argument.type, constructor.parameterTypes.getOrNull(index), scope)
            }
            mv.visitMethodInsn(
                INVOKESPECIAL,
                type.internalName,
                "<init>",
                descriptorOf(constructor.parameterTypes, VoidType),
                false,
            )
            return
        }

        for (argument in expression.arguments) emitExpression(argument, scope)
        val descriptor = descriptorOf(expression.arguments.map { it.type }, VoidType)
        mv.visitMethodInsn(INVOKESPECIAL, type.internalName, "<init>", descriptor, false)
    }

    /**
     * Converts the value on the stack from [from] to the type [to] a parameter declares.
     *
     * Three conversions are possible in Neko, and all three are about the *target*: boxing a
     * primitive into the `Object` a type parameter erased to, boxing it into an `Object` parameter,
     * and boxing it into a nullable one 鈥?where the box is not a convenience but the only way the
     * value can be carried, since `int?` is an `Integer`. Narrowing is the fourth, for a `byte`,
     * `short` or `char` parameter. The reverse 鈥攗nboxing 鈥攊s the extractor's job at run time, because
     * only then is the concrete type known.
     */
    private fun emitCoerce(from: NekoType, to: NekoType?, scope: MethodScope) {
        if (to == null) return
        emitStoreValue(from, to, scope.mv)
    }

    private fun emitNamedCall(
        expression: CallExpression,
        symbol: FunctionSymbol,
        scope: MethodScope,
    ) {
        val mv = scope.mv
        val builtin = symbol.builtin
        if (builtin != null) {
            if (builtin.receiverIsSystemOut) {
                mv.visitFieldInsn(
                    GETSTATIC,
                    builtin.jvmOwner,
                    "out",
                    "Ljava/io/PrintStream;",
                )
            }
            for (argument in expression.arguments) emitExpression(argument, scope)
            mv.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/io/PrintStream",
                builtin.jvmName,
                builtin.jvmDescriptor,
                false,
            )
            return
        }

        // A bare call reaches an instance function through the receiver the method already has in
        // slot 0, so `this` goes on the stack first; a static one is an ordinary `invokestatic`.
        if (symbol.isStatic) {
            emitArguments(expression.arguments, symbol.parameterTypes, scope)
            mv.visitMethodInsn(
                INVOKESTATIC,
                symbol.jvmOwner,
                symbol.name,
                descriptorOf(symbol.parameterTypes, symbol.returnType),
                false,
            )
            return
        }

        mv.visitVarInsn(ALOAD, 0)
        emitArguments(expression.arguments, symbol.parameterTypes, scope)
        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            symbol.jvmOwner,
            symbol.name,
            descriptorOf(symbol.parameterTypes, symbol.returnType),
            false,
        )
    }

    // ---------------------------------------------------------------- lambdas

    /**
     * Emits a lambda literal.
     *
     * The literal becomes `new <Owner>$Lambda$n`, with each captured variable copied in through the
     * constructor, so the resulting value is an instance of the function type's interface.
     */
    private fun emitLambda(expression: LambdaExpression, scope: MethodScope) {
        val functionType = expression.type as? FunctionType ?: run {
            diagnostics.error(
                "internal error: a lambda reached code generation without a resolved function type",
                expression.span,
            )
            scope.mv.visitInsn(ACONST_NULL)
            return
        }

        val interfaceName = functionInterfaceName(functionType)
        val lambdaClassName = "$currentOwner\$Lambda\$${lambdaCounter++}"
        val identity = expression.identity ?: Any()

        // Everything the body reads from an enclosing scope has to travel in as a field. Nested
        // lambdas are walked too, so an outer lambda forwards whatever an inner one needs.
        val referenced = LinkedHashSet<LocalSymbol>()
        collectLocalReferences(expression.body, referenced)
        val captures = mutableListOf<Pair<LocalSymbol, String>>()
        for (symbol in referenced) {
            if (symbol.enclosingLambda === identity) continue // Declared inside this lambda.
            // Reachable either as a slot of this method or as a field of the enclosing lambda.
            if (scope.slotOf(symbol) == null && scope.captureOf(symbol) == null) continue
            captures += symbol to "captured\$${captures.size}"
        }

        val enclosingOwner = currentOwner
        emitLambdaClass(lambdaClassName, interfaceName, functionType, expression, captures, identity)
        currentOwner = enclosingOwner

        val mv = scope.mv
        mv.visitTypeInsn(NEW, lambdaClassName)
        mv.visitInsn(DUP)

        for ((symbol, _) in captures) {
            val slot = scope.slotOf(symbol)
            if (slot != null) {
                mv.visitVarInsn(loadOpcode(symbol.type), slot)
            } else {
                mv.visitVarInsn(ALOAD, 0)
                mv.visitFieldInsn(
                    GETFIELD,
                    scope.owner,
                    scope.captureOf(symbol)!!,
                    descriptorOf(symbol.type),
                )
            }
        }

        val constructorDescriptor = descriptorOf(captures.map { it.first.type }, VoidType)
        mv.visitMethodInsn(INVOKESPECIAL, lambdaClassName, "<init>", constructorDescriptor, false)
    }

    /** Emits the class that implements a lambda literal. */
    private fun emitLambdaClass(
        lambdaClassName: String,
        interfaceName: String,
        functionType: FunctionType,
        expression: LambdaExpression,
        captures: List<Pair<LocalSymbol, String>>,
        identity: Any,
    ) {
        currentOwner = lambdaClassName

        val writer = NekoClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(
            V1_8,
            ACC_FINAL or ACC_SUPER,
            lambdaClassName,
            null,
            "java/lang/Object",
            arrayOf(interfaceName),
        )
        writer.visitSource(currentFile.sourceName(), null)

        for ((symbol, fieldName) in captures) {
            writer.visitField(
                ACC_PRIVATE or ACC_FINAL,
                fieldName,
                descriptorOf(symbol.type),
                null,
                null,
            ).visitEnd()
        }

        emitLambdaConstructor(writer, lambdaClassName, captures)

        val mv = writer.visitMethod(
            ACC_PUBLIC,
            INVOKE_METHOD_NAME,
            descriptorOf(functionType.parameters, functionType.returnType),
            null,
            null,
        )
        mv.visitCode()

        // `invoke` is an instance method, so `this` occupies slot 0 and parameters follow.
        val scope = MethodScope(
            owner = lambdaClassName,
            mv = mv,
            lambdaIdentity = identity,
            firstSlot = 1,
            captures = captures.associate { it.first to it.second },
        )

        for (parameter in expression.parameterSymbols.orEmpty()) {
            scope.allocate(parameter)
        }

        for (statement in expression.body.statements) {
            emitStatement(statement, scope)
        }
        emitDefaultReturn(functionType.returnType, mv)

        mv.visitMaxs(0, 0)
        mv.visitEnd()
        writer.visitEnd()

        output[lambdaClassName] = writer.toByteArray()
    }

    private fun emitLambdaConstructor(
        writer: ClassWriter,
        lambdaClassName: String,
        captures: List<Pair<LocalSymbol, String>>,
    ) {
        val descriptor = descriptorOf(captures.map { it.first.type }, VoidType)
        val mv = writer.visitMethod(ACC_PUBLIC, "<init>", descriptor, null, null)
        mv.visitCode()
        mv.visitVarInsn(ALOAD, 0)
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)

        captures.forEachIndexed { index, (symbol, fieldName) ->
            mv.visitVarInsn(ALOAD, 0)
            // Captured values follow `this`, which occupies slot 0.
            mv.visitVarInsn(loadOpcode(symbol.type), index + 1)
            mv.visitFieldInsn(PUTFIELD, lambdaClassName, fieldName, descriptorOf(symbol.type))
        }

        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * Emits one interface per distinct function type.
     *
     * Interfaces are created on demand by [functionInterfaceName] while bodies are being emitted, so
     * they are written out once the whole file has been walked.
     */
    private fun emitFunctionInterfaces() {
        for ((type, internalName) in functionInterfaces) {
            val writer = NekoClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
            writer.visit(
                V1_8,
                ACC_PUBLIC or ACC_ABSTRACT or ACC_INTERFACE,
                internalName,
                null,
                "java/lang/Object",
                null,
            )
            writer.visitSource(currentFile.sourceName(), null)
            writer.visitMethod(
                ACC_PUBLIC or ACC_ABSTRACT,
                INVOKE_METHOD_NAME,
                descriptorOf(type.parameters, type.returnType),
                null,
                null,
            ).visitEnd()
            writer.visitEnd()
            output[internalName] = writer.toByteArray()
        }
    }

    /**
     * The interface representing [type], registering it if this is the first use.
     *
     * The interface is emitted into the package of the class that first needed it, which keeps a
     * lambda type used by one file from colliding with an unrelated file's.
     */
    private fun functionInterfaceName(type: FunctionType): String =
        functionInterfaces.getOrPut(type) {
            "${packagePrefix()}$INTERFACE_PREFIX${functionInterfaces.size}"
        }

    // ------------------------------------------------------------ descriptors

    private fun descriptorOf(type: NekoType): String = when (type) {
        ByteType -> "B"
        ShortType -> "S"
        CharType -> "C"
        IntType -> "I"
        LongType -> "J"
        FloatType -> "F"
        DoubleType -> "D"
        BoolType -> "Z"
        StringType -> "Ljava/lang/String;"
        VoidType -> "V"
        ObjectType -> "Ljava/lang/Object;"
        ThrowableType -> "Ljava/lang/Throwable;"
        is ArrayType -> "[" + descriptorOf(type.element)
        is FunctionType -> "L" + functionInterfaceName(type) + ";"
        is ClassType -> "L" + type.internalName + ";"
        // A nullable type is carried as a reference whatever it wraps, so `int?` is a
        // `java.lang.Integer` and `Point?` is a `Point`. That is what makes `null` storable in it:
        // a JVM primitive has no `null` to be absent as. A nullable *reference* type is unchanged 鈥?        // `string?` is the same `String` `string` already is 鈥?so only a nullable primitive needs
        // its box named here.
        is NullableType -> nullableDescriptorOf(type)
        ErrorType -> "Ljava/lang/Object;"
    }

    /** The descriptor a nullable [type] is carried as: its inner type's box, or its inner type. */
    private fun nullableDescriptorOf(type: NullableType): String {
        val inner = type.inner
        val boxed = if (BuiltinTypes.isPrimitive(inner)) {
            BuiltinTypes.referenceTypeNameOf(inner)
        } else {
            null
        }
        return if (boxed == null) descriptorOf(inner) else "L${boxed.replace('.', '/')};"
    }

    /**
     * The `*aload` that reads one element of an array of [element].
     *
     * Three of these are worth naming rather than looking up: `byte` and `bool` share `BALOAD` because
     * the JVM has one array type for both, and `byte`, `short` and `char` are all `int`s on the stack,
     * so only the *array* distinguishes them.
     */
    private fun arrayLoadOpcode(element: NekoType): Int = when (element) {
        ByteType, BoolType -> BALOAD
        ShortType -> SALOAD
        CharType -> CALOAD
        IntType -> IALOAD
        LongType -> LALOAD
        FloatType -> FALOAD
        DoubleType -> DALOAD
        else -> AALOAD
    }

    /** The `*astore` that writes one element of an array of [element]. */
    private fun arrayStoreOpcode(element: NekoType): Int = when (element) {
        ByteType, BoolType -> BASTORE
        ShortType -> SASTORE
        CharType -> CASTORE
        IntType -> IASTORE
        LongType -> LASTORE
        FloatType -> FASTORE
        DoubleType -> DASTORE
        else -> AASTORE
    }

    private fun descriptorOf(parameters: List<NekoType>, returnType: NekoType): String =
        descriptorOf(parameters, descriptorOf(returnType))

    /** The same, for a return type whose descriptor is already known, such as `[Ljava/lang/Object;`. */
    private fun descriptorOf(parameters: List<NekoType>, returnDescriptor: String): String = buildString {
        append('(')
        for (parameter in parameters) append(descriptorOf(parameter))
        append(')')
        append(returnDescriptor)
    }

    /**
     * The opcode that reads a value of [type] out of a local.
     *
     * `byte`, `short`, `char`, `int` and `bool` all share `ILOAD`: the JVM has one `int` slot type, and
     * the narrower ones are distinguished by *how many bits are meaningful*, not by how they are
     * carried. `long`, `float` and `double` each have their own.
     */
    private fun loadOpcode(type: NekoType): Int = when (type) {
        ByteType, ShortType, CharType, IntType, BoolType -> ILOAD
        LongType -> LLOAD
        FloatType -> FLOAD
        DoubleType -> DLOAD
        else -> ALOAD
    }

    private fun storeOpcode(type: NekoType): Int = when (type) {
        ByteType, ShortType, CharType, IntType, BoolType -> ISTORE
        LongType -> LSTORE
        FloatType -> FSTORE
        DoubleType -> DSTORE
        else -> ASTORE
    }

    private fun returnOpcode(type: NekoType): Int = when (type) {
        VoidType -> RETURN
        ByteType, ShortType, CharType, IntType, BoolType -> IRETURN
        LongType -> LRETURN
        FloatType -> FRETURN
        DoubleType -> DRETURN
        else -> ARETURN
    }

    /**
     * The opcode that discards a value of [type].
     *
     * A `long` or a `double` occupies two slots, so discarding one takes `POP2`; using `POP` there
     * would leave half the value on the stack and every frame after it would be wrong.
     */
    private fun popOpcode(type: NekoType): Int =
        if (BuiltinTypes.slotCountOf(type) == 2) POP2 else POP

    private fun pushDefaultOpcode(type: NekoType): Int = when (type) {
        ByteType, ShortType, CharType, IntType, BoolType -> ICONST_0
        LongType -> LCONST_0
        FloatType -> FCONST_0
        DoubleType -> DCONST_0
        else -> ACONST_NULL
    }

    private fun pushDefault(type: NekoType, mv: MethodVisitor) {
        mv.visitInsn(pushDefaultOpcode(type))
    }

    private fun emitDefaultReturn(returnType: NekoType, mv: MethodVisitor) {
        when (returnType) {
            VoidType -> mv.visitInsn(RETURN)
            ByteType, ShortType, CharType, IntType, BoolType -> {
                mv.visitInsn(ICONST_0)
                mv.visitInsn(IRETURN)
            }

            LongType -> {
                mv.visitInsn(LCONST_0)
                mv.visitInsn(LRETURN)
            }

            FloatType -> {
                mv.visitInsn(FCONST_0)
                mv.visitInsn(FRETURN)
            }

            DoubleType -> {
                mv.visitInsn(DCONST_0)
                mv.visitInsn(DRETURN)
            }

            else -> {
                mv.visitInsn(ACONST_NULL)
                mv.visitInsn(ARETURN)
            }
        }
    }

    private fun popIfValue(type: NekoType, mv: MethodVisitor) {
        if (type != VoidType && type !== ErrorType) mv.visitInsn(popOpcode(type))
    }

    /**
     * Emits the most compact instruction that pushes [value] as an `int`.
     *
     * Only the `int`-shaped types use this: a `byte`, `short` or `char` literal is still an `int` on
     * the stack, and the narrowing back happens where the value is stored.
     */
    private fun pushInt(value: Int, mv: MethodVisitor) {
        when (value) {
            -1 -> mv.visitInsn(ICONST_M1)
            0 -> mv.visitInsn(ICONST_0)
            1 -> mv.visitInsn(ICONST_1)
            2 -> mv.visitInsn(ICONST_2)
            3 -> mv.visitInsn(ICONST_3)
            4 -> mv.visitInsn(ICONST_4)
            5 -> mv.visitInsn(ICONST_5)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(BIPUSH, value)
            in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(SIPUSH, value)
            else -> mv.visitLdcInsn(value)
        }
    }

    /** Emits the most compact instruction that pushes [value] as a `long`. */
    private fun pushLong(value: Long, mv: MethodVisitor) {
        when (value) {
            0L -> mv.visitInsn(LCONST_0)
            1L -> mv.visitInsn(LCONST_1)
            else -> mv.visitLdcInsn(value)
        }
    }

    /**
     * Emits the most compact instruction that pushes [value] as a `float`.
     *
     * The three constants the JVM has a dedicated opcode for are the ones a program writes most often;
     * everything else 鈥?including `-0.0f`, which `FCONST_0` would turn into `+0.0f` 鈥?is a constant
     * pool entry, which ASM emits as `LDC` for a `Float` because that is the only width a `float`
     * constant has.
     */
    private fun pushFloat(value: Float, mv: MethodVisitor) {
        when {
            value == 0.0f && 1.0f / value == Float.POSITIVE_INFINITY -> mv.visitInsn(FCONST_0)
            value == 1.0f -> mv.visitInsn(FCONST_1)
            value == 2.0f -> mv.visitInsn(FCONST_2)
            else -> mv.visitLdcInsn(value)
        }
    }

    private fun pushDouble(value: Double, mv: MethodVisitor) {
        when {
            value == 0.0 && 1.0 / value == Double.POSITIVE_INFINITY -> mv.visitInsn(DCONST_0)
            value == 1.0 -> mv.visitInsn(DCONST_1)
            else -> mv.visitLdcInsn(value)
        }
    }

    private fun markLine(mv: MethodVisitor, node: Node) {
        if (node.span.isEmpty) return
        val label = Label()
        mv.visitLabel(label)
        mv.visitLineNumber(node.span.line, label)
    }

    /**
     * Records every local variable read anywhere under [node], including inside nested lambdas.
     *
     * Walking nested lambdas is what makes a doubly-nested closure work: the outer lambda captures
     * whatever the inner one needs, and the inner one then captures it from the outer lambda's
     * fields.
     */
    private fun collectLocalReferences(node: Node, into: MutableSet<LocalSymbol>) {
        when (node) {
            is NameExpression -> (node.resolution as? LocalSymbol)?.let { into += it }

            is BlockStatement -> node.statements.forEach { collectLocalReferences(it, into) }
            is VariableDeclarationStatement -> node.initializer?.let { collectLocalReferences(it, into) }
            is AssignmentStatement -> {
                collectLocalReferences(node.target, into)
                collectLocalReferences(node.value, into)
            }

            is ExpressionStatement -> collectLocalReferences(node.expression, into)
            is ReturnStatement -> node.value?.let { collectLocalReferences(it, into) }
            is YieldStatement -> collectLocalReferences(node.value, into)
            // A `nya`'s blocks are emitted inline, in whatever method the construct is written in, so
            // whatever they read from an enclosing scope has to travel as far as the construct does 鈥?            // which is the same reason a nested block's reads are collected.
            is NyaExpression -> {
                collectLocalReferences(node.condition, into)
                node.failureBlock?.let { collectLocalReferences(it, into) }
                node.elseBlock?.let { collectLocalReferences(it, into) }
            }
            is IfStatement -> {
                collectLocalReferences(node.condition, into)
                collectLocalReferences(node.thenBranch, into)
                node.elseBranch?.let { collectLocalReferences(it, into) }
            }

            is WhileStatement -> {
                collectLocalReferences(node.condition, into)
                collectLocalReferences(node.body, into)
            }

            is WhenStatement -> {
                collectLocalReferences(node.subject, into)
                for (branch in node.branches) {
                    branch.condition?.let { collectLocalReferences(it, into) }
                    collectLocalReferences(branch.body, into)
                }
            }

            is IndexAssignmentStatement -> {
                collectLocalReferences(node.array, into)
                collectLocalReferences(node.index, into)
                collectLocalReferences(node.value, into)
            }

            is IndexExpression -> {
                collectLocalReferences(node.array, into)
                collectLocalReferences(node.index, into)
            }

            is ThisExpression -> Unit

            is ChainedComparisonExpression -> node.operands.forEach { collectLocalReferences(it, into) }

            is BinaryExpression -> {
                collectLocalReferences(node.left, into)
                collectLocalReferences(node.right, into)
            }

            is UnaryExpression -> collectLocalReferences(node.operand, into)

            is CallExpression -> {
                collectLocalReferences(node.callee, into)
                node.arguments.forEach { collectLocalReferences(it, into) }
            }

            is LambdaExpression -> {
                collectLocalReferences(node.body, into)
                // Parameter names declared by the nested lambda are locals of *that* lambda, so
                // their `enclosingLambda` differs and the caller's filter excludes them.
            }

            is MemberAccessExpression -> collectLocalReferences(node.receiver, into)

            // A `try` extractor becomes a class of its own, emitted inside whichever method it
            // appears in 鈥攕o if that method is a lambda, whatever the extractor reads from the
            // enclosing scope has to be forwarded by the lambda first, exactly as a nested lambda's
            // reads are. Its own parameters are locals of the generated `invoke`; they are never
            // allocated in this scope, so the caller's filter drops them.
            is TryStatement -> {
                collectLocalReferences(node.call, into)
                collectLocalReferences(node.extractorBody, into)
            }

            is TryExpression -> {
                collectLocalReferences(node.call, into)
                collectLocalReferences(node.extractorBody, into)
            }

            // A `purr` block is emitted inline, in this very method, so whatever it reads from an
            // enclosing scope travels exactly as far as the `purr` does.
            is PurrStatement -> collectLocalReferences(node.body, into)

            else -> Unit
        }
    }

    /** Per-method state: local variable slots, the owner class, and lambda capture fields. */
    private class MethodScope(        val owner: String,
        val mv: MethodVisitor,
        val lambdaIdentity: Any?,
        firstSlot: Int,
        val captures: Map<LocalSymbol, String> = emptyMap(),
        /**
         * `true` when the method has no `this`, so slot 0 does not hold one.
         *
         * A bare reference to an instance field reaches it through `this`, which only exists in a
         * non-static method.
         */
        val static: Boolean = true,
        /** The function whose name prefixes the `raise` builders this method registers. */
        val builderPrefix: String = "emit",
        /**
         * The type this method answers, which is what a `return` inside it has to be narrowed to.
         *
         * A `byte`-returning member that ends in `return a + b` leaves an `int` on the stack and has to
         * narrow it like any other store into a `byte`; nothing in the `return` statement itself names
         * the method's type, so the scope carries it.
         */
        val returnType: NekoType = VoidType,
        /**
         * `true` when this method is the `invoke` of a value-returning `try` extractor.
         *
         * A `yield` in such a body produces that `invoke`'s value, i.e. it returns a *boxed* `Object`
         * rather than a value of the enclosing language's type 鈥攖he interface erases its `R` to
         * `Object`, so the `int` a `yield` names has to be boxed on the way out.
         */
        val extractorResult: Boolean = false,
        /**
         * The `nya` value form currently being emitted inside this method, if any.
         *
         * Unlike a lambda or a `try` extractor, whose body becomes a method of its own, a `nya`'s
         * blocks are emitted inline 鈥?so the `yield` that appears in one is emitted while this very
         * scope is current, and this is how it finds the slot to fill and the label to jump to. It is
         * part of the scope rather than a generator field because a `nya` inside a lambda is in a
         * different method from a `nya` outside it, and only the innermost enclosing one is the home
         * of a given `yield`.
         */
        var nyaResult: NyaResult? = null,
        /**
         * The `purr` blocks currently open in this method, innermost last.
         *
         * A `return` or a `yield` has to release every lock it is leaving, not just the innermost: a
         * `return` inside `purrcls { purr { return x } }` leaves both. So this is a stack and the
         * releases are emitted from the top down, which is the order `javac` nests its `finally`
         * bodies in.
         *
         * It lives on the scope rather than on the generator because a lambda's body is a different
         * method, where an enclosing `purr`'s stamp is not a local at all 鈥?so a `return` there must
         * *not* try to unlock.
         */
        val purr: MutableList<PurrScope> = mutableListOf(),
    ) {
        private var nextSlot = firstSlot
        private val slots = LinkedHashMap<LocalSymbol, Int>()

        /** Slot for [symbol] in this method, or `null` when it is a captured variable. */
        fun slotOf(symbol: LocalSymbol): Int? = slots[symbol]

        /** Capture field name for [symbol] in this lambda, or `null`. */
        fun captureOf(symbol: LocalSymbol): String? = captures[symbol]

        /**
         * Associates [symbol] with the next free slot and returns it.
         *
         * The slot count comes from the symbol's *type*, not from a fixed one per local. A `long` or a
         * `double` occupies two slots in the JVM's frame, and handing out one leaves the second half of
         * the value overlapping whatever is allocated next: the class still verifies whenever the
         * overlapping slot is another two-slot value or an unused one, and computes garbage silently,
         * which is exactly the kind of mistake a test that only checks "it compiled" would miss.
         */
        fun allocate(symbol: LocalSymbol): Int {
            val existing = slots[symbol]
            if (existing != null) return existing
            val slot = nextSlot
            nextSlot += BuiltinTypes.slotCountOf(symbol.type)
            slots[symbol] = slot
            return slot
        }

        /**
         * Associates [symbol] with a slot the caller has already chosen.
         *
         * Used where the slots are not this scope's to hand out: a `try` extractor's parameters are
         * unboxed into the fixed block of locals that follows the `Object`s the interface passes, and
         * the body still has to find them by symbol. The next temporary starts after the whole block,
         * which is what the widest of the parameters decides.
         */
        fun reserve(symbol: LocalSymbol, slot: Int) {
            slots[symbol] = slot
        }

        /**
         * Reserves a slot for a compiler-generated temporary holding a value of [type].
         *
         * Temporaries are never reused, so the slot map stays a pure function of the source and the
         * stack-map frames ASM computes always agree with the code emitted. The type is required
         * rather than optional because the two-slot types are the ones that need it, and a default
         * would make it easiest to forget exactly there.
         */
        fun allocateTemp(type: NekoType): Int {
            val slot = nextSlot
            nextSlot += BuiltinTypes.slotCountOf(type)
            return slot
        }

        /**
         * Moves the next free slot past [slot], which the caller has already filled.
         *
         * A block of locals allocated by hand 鈥?an extractor's unboxed parameters 鈥?has to be walked
         * over before this scope hands out a temporary, or the temporary lands on top of one of them.
         */
        fun advancePast(slot: Int) {
            nextSlot = maxOf(nextSlot, slot)
        }
    }

    /** One open `purr` block: which lock it holds, where the field is, and the stamp. */
    private class PurrScope(
        val lockType: PurrLock,
        val lockField: String,
        val stampSlot: Int,
    )

    /**
     * A [ClassWriter] that never needs the class loader.
     *
     * Stack-map frames occasionally need a common supertype of two reference types;
     * `java/lang/Object` is always a valid (if imprecise) answer, and it keeps code generation
     * independent of whether the classes mentioned have already been written to disk.
     */
    private class NekoClassWriter(flags: Int) : ClassWriter(flags) {
        override fun getCommonSuperClass(type1: String, type2: String): String = "java/lang/Object"
    }

    private companion object {
        /** Name of the single method on every generated function-type interface. */
        const val INVOKE_METHOD_NAME = "invoke"

        /** Prefix of the generated function-type interfaces, e.g. `Neko$Function$0`. */
        const val INTERFACE_PREFIX = "Neko\$Function\$"

        /** Simple name of the standard library exception a `raise` throws and a `try` catches. */
        const val EARLY_EMIT_EXCEPTION_CLASS = "EarlyEmitException"

        /** Simple name of the standard library interface every `EmitExtractorN` extends. */
        const val EMIT_EXTRACTOR_CLASS = "EmitExtractor"

        /** Prefix of the value-returning extractor family, e.g. `EmitExtractorWithResult2`. */
        const val EMIT_EXTRACTOR_WITH_RESULT_PREFIX = "EmitExtractorWithResult"

        /**
         * The JDK error a failed `nya` throws, as an internal name.
         *
         * It belongs to the platform rather than to the Neko standard library, so unlike
         * [EARLY_EMIT_EXCEPTION_CLASS] it needs no lookup: `java.lang` is on every JVM that could
         * load the class this code goes into. Using the same error Java's own `assert` throws is
         * what makes a Neko check and a Java one fail in the way a reader already expects.
         */
        const val ASSERTION_ERROR_CLASS = "java/lang/AssertionError"

        /**
         * The synthetic field holding an enum's constants, as `javac` names it.
         *
         * The `$` prefix is what marks it as compiler-created rather than source-written, and the
         * name is the one every Java decompiler and reflection helper already recognises.
         */
        const val VALUES_FIELD = "\$VALUES"

        /**
         * `java.util.concurrent.locks.StampedLock`, the lock a `purr` block holds.
         *
         * Not in `java.lang`, which is the only platform package a Neko source file can name 鈥?see
         * `Analyzer.resolveClassType` 鈥?so a program can neither declare one nor call its methods.
         * That is exactly why `purr` is a keyword: the acquisition and release are bytecode the
         * compiler writes, and the stamp `unlock` needs is a local, not something the source can
         * carry.
         */
        const val STAMPED_LOCK_CLASS = "java/util/concurrent/locks/StampedLock"
    }
}

/** The name shown in diagnostics and recorded as the class-file source attribute. */
private fun NekoFile?.sourceName(): String =
    this?.span?.file?.name?.substringAfterLast('/')?.substringAfterLast('\\') ?: "unknown.neko"

/**
 * Whether this is one of the primitives the JVM *cannot* compare, load or store with the `int`
 * instructions: `long`, `float` and `double`.
 *
 * These are also the three that occupy two local slots, which is not a coincidence 鈥?both facts come
 * from the value being wider than the 32 bits the `int`-shaped instructions are built around 鈥?but
 * they are asked about separately because they are used separately: a `float` needs `fcmpl` where a
 * `long` needs `lcmp`, while only a *count* of slots is needed to size a local.
 */
private val NekoType.isWidePrimitive: Boolean
    get() = this == LongType || this == FloatType || this == DoubleType
