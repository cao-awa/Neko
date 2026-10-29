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
 *  * **`scratch` calls, `yield` throws, `nyan` returns.** A `try`'s `{ ... }` block becomes a
 *    `public static` method of the class holding the `try`, taking the `emit` clause's values as its
 *    parameters. A `scratch v1, v2` is an `invokestatic` against that method and carries on with
 *    whatever it answers — it does **not** throw, and passes nothing through a carrier array. `nyan`
 *    is a plain `return` from that method. `yield` is the only departure: it throws
 *    `EmitYieldException` carrying its value, and the `try` that declared the handler catches it.
 *  * **A `try` may be worth a value.** Both forms meet at one slot: the call's own return value when
 *    it returned, or the exception's payload when a `yield` ran. The field is one `Object` wide
 *    because the two paths can disagree on the JVM type, so each is boxed on the way in and unboxed
 *    to the `try`'s own type on the way out.
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
            // The declaration carries its own symbol, which is the only way to be sure which of two
            // same-named classes in different packages this is.
            val symbol = declaration.symbol ?: continue
            generateClass(declaration, symbol)
        }

        // Function-type interfaces are discovered lazily while emitting bodies, so emit them last.
        emitFunctionInterfaces()
        return output
    }

    /**
     * Numbers the handler classes one method produces, so a program with several `try` blocks in
     * the same function gets one class per `try` rather than two classes with the same name.
     */
    private var scratchHandlerCounter = 0


    /**
     * The internal name of a standard library class, looked up in the program.
     *
     * The library is written in Neko and compiled alongside the program, so `Iterable` is a class
     * this very run will emit — its internal name comes from the declaration rather than from an
     * assumption about the package. The fallback keeps code generation working when the library is
     * absent, so the reader sees the analyzer's "the library is missing" diagnostic rather than a
     * crash here.
     */
    private fun standardLibraryClass(simpleName: String): String =
        classes.values.firstOrNull { it.name == simpleName }?.jvmInternalName
            ?: (currentClass?.packagePrefix ?: "") + simpleName

    /**
     * The JVM name of `EmitYieldException`, the checked exception a `yield` throws.
     *
     * Written in Neko like the rest of the standard library, so its package comes from the source that
     * declares it — `standardLibraryClass` reads the symbol the analyzer resolved. A program compiled
     * without that source therefore names a class that does not exist, which is the correct failure:
     * the analyzer reports the missing declaration by name rather than this file inventing a location
     * for it.
     */
    private fun emitYieldExceptionClass(): String = standardLibraryClass("EmitYieldException")

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
            is ForStatement -> collectPurrLocks(statement.body, used)
            is ForEachStatement -> collectPurrLocks(statement.body, used)
            is IncrementStatement -> Unit
            is WhenStatement -> statement.branches.forEach { collectPurrLocks(it.body, used) }
            is TryStatement -> collectPurrLocks(statement.extractorBody, used)
            is ExpressionStatement -> collectPurrLocks(statement.expression, used)
            is VariableDeclarationStatement -> collectPurrLocks(statement.initializer, used)
            is ReturnStatement -> collectPurrLocks(statement.value, used)
            is YieldStatement -> collectPurrLocks(statement.value, used)
            is AssignmentStatement -> collectPurrLocks(statement.value, used)
            is IndexAssignmentStatement -> collectPurrLocks(statement.value, used)
            is NyanStatement -> collectPurrLocks(statement.value, used)
            is TryScratchStatement -> {
                collectPurrLocks(statement.call, used)
                collectPurrLocks(statement.scratch, used)
            }
            // A group opener declares nothing runnable, and a `blep` branch is only ever emitted as
            // part of the `try` that installs the group, so neither contributes a lock of its own.
            is PreGroupDeclaration -> Unit
            is BlepDeclaration -> collectPurrLocks(statement.body, used)
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
     * `a + b` where the expression's own type is `string`, so at least one side is a `string`.
     *
     * ```
     *   new StringBuilder
     *   dup
     *   invokespecial <init>()V
     *   <left>                         // emitted in its own type
     *   invokevirtual append(<left>)    // the overload that type needs
     *   <right>
     *   invokevirtual append(<right>)
     *   invokevirtual toString()Ljava/lang/String;
     * ```
     *
     * **Why not `String.concat`.** That method takes a `String` and nothing else, so it could only
     * ever have served `string + string`. The reason to write `"n = " + count` is precisely that
     * `count` is not a string, so the operand has to go through `append`, which has an overload per
     * type and prints a class instance with its own `toString`.
     *
     * **Why not `invokedynamic`.** The `StringConcatFactory` call site is what `javac` emits for Java
     * 9 and later, and it is faster. It is not used here because it would tie the generated class to
     * a bootstrap method the language does not otherwise need: `StringBuilder` works on every JVM the
     * language targets, and a reader debugging a generated class can see the appends one by one.
     *
     * The operand types are read off the **analysed** expressions, not off the operands' syntax, so a
     * `byte` appends as `(I)` — there is no `append(byte)` — and a `char` as `(C)`, which is the
     * difference between printing a letter and printing its code point.
     */
    private fun emitConcatenation(expression: BinaryExpression, scope: MethodScope) {
        val mv = scope.mv
        mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
        mv.visitInsn(DUP)
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)

        for (operand in listOf(expression.left, expression.right)) {
            emitExpression(operand, scope)
            // A nullable operand is carried boxed, which is what `append(Object)` expects; the same
            // descriptor covers `Object`, a class instance and `string` itself. `VoidType` cannot
            // reach here — the analyzer refuses to concatenate a call that produces no value.
            mv.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                appendDescriptorOf(operand.type),
                false,
            )
        }

        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            "java/lang/StringBuilder",
            "toString",
            "()Ljava/lang/String;",
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
        // The written parameters plus, for a function with an `emit` clause, the hidden
        // `ScratchHandlerN` at the end. The source never mentions it, but the JVM must see it, so the
        // descriptor is built from the wider list.
        val descriptor = descriptorOf(symbol.descriptorParameterTypes, returnType)
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

        // A `try` whose handler can `yield` makes this method able to throw `EmitYieldException`, and
        // so does calling any function that can. The attribute is advisory for the JVM, but it is what
        // a reader of the class file uses to see the departure, so it is recorded whenever either is
        // true — not only when this very function declares an `emit` clause.
        val declaredExceptions = when {
            symbol.canRaise -> arrayOf(emitYieldExceptionClass())
            function.body != null && function.body.reachesYield() -> arrayOf(emitYieldExceptionClass())
            else -> null
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
            returnType = returnType,
        )

        for (parameter in parametersByFunction[function].orEmpty()) {
            scope.allocate(parameter)
        }

        // The handler follows the written parameters in the frame. Nothing in the source names it, so
        // it is not a `LocalSymbol`: the slot is recorded on the scope and `scratch` reads it there.
        symbol.handlerType?.let { handlerType ->
            scope.handlerSlot = scope.allocateTemp(handlerType)
            scope.handlerInterface = (handlerType as? ClassType)?.internalName
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
            is ForStatement -> emitFor(statement, scope)
            is ForEachStatement -> emitForEach(statement, scope)
            is IncrementStatement -> emitIncrement(statement, scope)
            is WhenStatement -> emitWhen(statement, scope)
            is NyanStatement -> emitNyan(statement, scope)
            is TryStatement -> emitTry(statement, scope)
            is TryScratchStatement -> emitTryScratch(statement, scope, resultSlot = null)
            is PurrStatement -> emitPurr(statement, scope)
            // A group opener is a declaration only: it installs nothing, and its branches reach the
            // generated `dispatch` through the `try` that closes the group.
            is PreGroupDeclaration -> Unit
            is BlepDeclaration -> Unit
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
    /**
     * `nyan value` — leaves the enclosing `try` handler, answering the value.
     *
     * The handler is a method, so this is simply a `return` from it: the value is converted to the
     * method's own return type and returned. That is the whole difference from `yield`, which throws
     * instead — see [emitYield].
     */
    private fun emitNyan(statement: NyanStatement, scope: MethodScope) {
        val value = statement.value
        val returnType = scope.returnType
        if (value == null) {
            // A bare `nyan` answers nothing. In a handler that is an `Object` method, so "nothing" is
            // the null answer — which a `scratch` site wanting a primitive turns into that type's
            // default rather than an exception. See [emitScratchAnswer].
            if (returnType === VoidType || returnType === ErrorType) {
                emitPurrReleaseOnExit(scope)
                scope.mv.visitInsn(RETURN)
            } else {
                emitPurrReleaseOnExit(scope)
                scope.mv.visitInsn(ACONST_NULL)
                scope.mv.visitInsn(ARETURN)
            }
            return
        }
        emitExpression(value, scope)
        // A handler's `dispatch` answers `Object`, so the value is boxed on the way out; every other
        // method it is narrowed to its own return type as before.
        if (returnType === VoidType || returnType === ErrorType) {
            emitPurrReleaseOnExit(scope)
            scope.mv.visitInsn(returnOpcode(value.type))
            return
        }
        emitStoreValue(value.type, returnType, scope.mv)
        // A `nyan` leaves the handler, and the handler runs inside the `try`'s protected region, so
        // any `purr` block entered inside it has to be released before the method returns.
        emitPurrReleaseOnExit(scope)
        scope.mv.visitInsn(returnOpcode(returnType))
    }

    /**
     * `yield value` — answers the `try` that declared the enclosing handler.
     *
     * A `yield` is *not* a `return`: it leaves through the `try`, which may be several calls up the
     * stack, so it throws `EmitYieldException` carrying the value. The `try` catches that and stores
     * the value as the construct's own result — which is why a `yield` can appear inside a function
     * the handler calls, not only in the handler itself.
     *
     * The other two forms of `yield` are the ones the language already had: inside a `nya` value form
     * it fills the shared slot and jumps to the join point, and inside a lambda it is that lambda's
     * return.
     */
    private fun emitYield(statement: YieldStatement, scope: MethodScope) {
        val value = statement.value
        // A bare `yield` carries nothing. In a lambda it still has to `return` — answering whatever
        // the lambda answers — so a `void` lambda is the only place it fits, and analysis has checked
        // that. In the throwing arm it carries `null` as the exception's payload, which no `try` will
        // read because only a value-wanting `try` accepts a valued `yield` in the first place.
        if (value == null) {
            val nya = scope.nyaResult
            if (nya != null) {
                scope.mv.visitInsn(ACONST_NULL)
                scope.mv.visitVarInsn(ASTORE, nya.slot)
                scope.mv.visitJumpInsn(GOTO, nya.end)
                return
            }
            if (scope.lambdaIdentity != null && !scope.extractorResult) {
                emitPurrReleaseOnExit(scope)
                scope.mv.visitInsn(RETURN)
                return
            }
            val mv = scope.mv
            val exceptionClass = emitYieldExceptionClass()
            val payloadSlot = scope.allocateTemp(ObjectType)
            mv.visitInsn(ACONST_NULL)
            mv.visitVarInsn(ASTORE, payloadSlot)
            mv.visitTypeInsn(NEW, exceptionClass)
            mv.visitInsn(DUP)
            mv.visitVarInsn(ALOAD, payloadSlot)
            mv.visitMethodInsn(INVOKESPECIAL, exceptionClass, "<init>", "(Ljava/lang/Object;)V", false)
            emitPurrReleaseOnExit(scope)
            mv.visitInsn(ATHROW)
            return
        }

        emitExpression(value, scope)

        val nya = scope.nyaResult
        if (nya != null) {
            emitStoreValue(value.type, ObjectType, scope.mv)
            scope.mv.visitVarInsn(ASTORE, nya.slot)
            scope.mv.visitJumpInsn(GOTO, nya.end)
            return
        }

        // A `yield` in a lambda is that lambda's return, whatever it is worth; a `yield` in a `try`
        // handler leaves through the `try` instead, which is the throwing arm below.
        if (scope.lambdaIdentity != null && !scope.extractorResult) {
            emitStoreValue(value.type, scope.returnType, scope.mv)
            val target = scope.returnType
            emitPurrReleaseOnExit(scope)
            scope.mv.visitInsn(
                returnOpcode(if (target === VoidType || target === ErrorType) value.type else target),
            )
            return
        }

        // `throw new EmitYieldException(value)`, with the value boxed because the exception carries a
        // single `Object` whatever the `emit` clause declared.
        //
        // The value is boxed into a local *before* the `NEW`, rather than left on the stack under the
        // new object: `new` pushes an uninitialized reference, and a value sitting beneath it makes the
        // verifier's frame disagree about what is where. Storing first keeps the stack empty at the
        // `NEW`, which is the shape every constructor call in this generator uses.
        val mv = scope.mv
        val exceptionClass = emitYieldExceptionClass()
        val payloadSlot = scope.allocateTemp(ObjectType)
        emitBox(statement.value.type, mv)
        mv.visitVarInsn(ASTORE, payloadSlot)

        mv.visitTypeInsn(NEW, exceptionClass)
        mv.visitInsn(DUP)
        mv.visitVarInsn(ALOAD, payloadSlot)
        mv.visitMethodInsn(
            INVOKESPECIAL,
            exceptionClass,
            "<init>",
            "(Ljava/lang/Object;)V",
            false,
        )
        emitPurrReleaseOnExit(scope)
        mv.visitInsn(ATHROW)
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
     * `for (i: int = 0; i < 100; i++) body` — the same shape as [emitWhile], with the two extra parts
     * pinned around it.
     *
     * ```
     *   <initializer>
     *   start: if (!condition) goto end
     *          <body>
     *   update: <update>
     *          goto start
     *   end:
     * ```
     *
     * The update label sits between the body and the back edge, which is what makes `continue` (if the
     * language ever grows one) run the update and why the label is emitted even though nothing jumps to
     * it yet. A missing condition emits no test at all, which is what makes `for (;;)` an endless loop
     * rather than a loop that never starts.
     */
    private fun emitFor(statement: ForStatement, scope: MethodScope) {
        val mv = scope.mv
        val startLabel = Label()
        val endLabel = Label()

        statement.initializer?.let { emitStatement(it, scope) }

        mv.visitLabel(startLabel)
        val condition = statement.condition
        if (condition != null) {
            markLine(mv, condition)
            emitExpression(condition, scope)
            mv.visitJumpInsn(IFEQ, endLabel)
        }

        emitStatement(statement.body, scope)
        statement.update?.let { update ->
            markLine(mv, update)
            emitStatement(update, scope)
        }
        mv.visitJumpInsn(GOTO, startLabel)
        mv.visitLabel(endLabel)
    }

    /**
     * `for (element: type in iterable) body` — the array case and the `Iterable` case are different
     * lowerings, chosen by the type the analyzer recorded.
     *
     * **An array** is walked with an index, because the JVM gives arrays no interface to implement:
     *
     * ```
     *   idx = 0
     *   start: if (idx >= xs.length) goto end
     *          element = xs[idx]
     *          <body>
     *          idx++
     *          goto start
     *   end:
     * ```
     *
     * **An `Iterable`** is walked with the three protocol calls, evaluated once for `iterator()` so the
     * body cannot accidentally restart the sequence:
     *
     * ```
     *   it = iterable.iterator()
     *   start: if (!it.hasNext()) goto end
     *          element = it.next()
     *          <body>
     *          goto start
     *   end:
     * ```
     *
     * Both bind the element into a local slot of the element's own type, so the body reads it exactly
     * as it reads any other local — including an unboxing for a reference element type, done once here
     * rather than on every read.
     */
    private fun emitForEach(statement: ForEachStatement, scope: MethodScope) {
        val mv = scope.mv
        val iterableType = statement.iterableType
        val elementType = statement.resolvedElementType ?: statement.elementType.let { ErrorType }

        markLine(mv, statement.iterable)
        emitExpression(statement.iterable, scope)

        if (iterableType is ArrayType) {
            emitForEachOverArray(statement, scope, elementType)
        } else {
            emitForEachOverIterable(statement, scope, elementType)
        }
    }

    /**
     * Gives the loop variable its slot and returns it.
     *
     * The name is a real local to everything inside the body — `println(element)` resolves to this
     * symbol — so the slot has to come from the same allocator every other local uses. A temporary
     * would compile the reads to a *different* slot and the body would see zero.
     */
    private fun declareLoopVariable(
        statement: ForEachStatement,
        elementType: NekoType,
        scope: MethodScope,
    ): Int {
        val symbol = statement.symbol
            ?: return scope.allocateTemp(elementType).also { statement.elementSlot = it }
        val slot = scope.slotOf(symbol) ?: scope.allocate(symbol)
        statement.elementSlot = slot
        return slot
    }

    /** The array lowering described at [emitForEach]; the array is already on the stack. */
    private fun emitForEachOverArray(
        statement: ForEachStatement,
        scope: MethodScope,
        elementType: NekoType,
    ) {
        val mv = scope.mv
        val arrayType = statement.iterableType as ArrayType
        val arraySlot = scope.allocateTemp(arrayType)
        val indexSlot = scope.allocateTemp(IntType)
        val startLabel = Label()
        val endLabel = Label()

        mv.visitVarInsn(ASTORE, arraySlot)
        mv.visitInsn(ICONST_0)
        mv.visitVarInsn(ISTORE, indexSlot)

        mv.visitLabel(startLabel)
        mv.visitVarInsn(ILOAD, indexSlot)
        mv.visitVarInsn(ALOAD, arraySlot)
        mv.visitInsn(ARRAYLENGTH)
        mv.visitJumpInsn(IF_ICMPGE, endLabel)

        mv.visitVarInsn(ALOAD, arraySlot)
        mv.visitVarInsn(ILOAD, indexSlot)
        mv.visitInsn(arrayLoadOpcode(arrayType.element))
        // An `int[]` read answers an `int`, and the loop variable was checked against it by the
        // analyzer, so the value goes straight into the slot that name resolves to.
        emitStoreValue(arrayType.element, elementType, mv)

        val elementSlot = declareLoopVariable(statement, elementType, scope)
        mv.visitVarInsn(storeOpcode(elementType), elementSlot)

        emitStatement(statement.body, scope)

        mv.visitIincInsn(indexSlot, 1)
        mv.visitJumpInsn(GOTO, startLabel)
        mv.visitLabel(endLabel)
    }

    /** The `Iterable` lowering described at [emitForEach]; the iterable is already on the stack. */
    private fun emitForEachOverIterable(
        statement: ForEachStatement,
        scope: MethodScope,
        elementType: NekoType,
    ) {
        val mv = scope.mv
        val iteratorSlot = scope.allocateTemp(ObjectType)
        val startLabel = Label()
        val endLabel = Label()

        // `iterator()` is called once, so the body cannot restart the sequence by asking again.
        mv.visitMethodInsn(INVOKEINTERFACE, ITERABLE_INTERFACE, "iterator", "()L$ITERATOR_INTERFACE;", true)
        mv.visitVarInsn(ASTORE, iteratorSlot)

        mv.visitLabel(startLabel)
        mv.visitVarInsn(ALOAD, iteratorSlot)
        mv.visitMethodInsn(INVOKEINTERFACE, ITERATOR_INTERFACE, "hasNext", "()Z", true)
        mv.visitJumpInsn(IFEQ, endLabel)

        mv.visitVarInsn(ALOAD, iteratorSlot)
        // `next()` erases to `Object` in the interface, whatever `T` was written as, so the descriptor
        // is the erased one and the value is unboxed below into the loop variable's own type.
        mv.visitMethodInsn(INVOKEINTERFACE, ITERATOR_INTERFACE, "next", "()Ljava/lang/Object;", true)
        // `next()` hands back an erased `Object`, so the element is turned into the loop variable's own
        // type here — once per element, not once per read. A reference type gets a `checkcast`, a
        // primitive its `xxxValue()` call.
        emitUnbox(elementType, mv)

        val elementSlot = declareLoopVariable(statement, elementType, scope)
        mv.visitVarInsn(storeOpcode(elementType), elementSlot)

        emitStatement(statement.body, scope)
        mv.visitJumpInsn(GOTO, startLabel)
        mv.visitLabel(endLabel)
    }

    /**
     * The `Iterable<T>` argument of [type], for deciding what `next()` is unwrapped from.
     *
     * The protocol's `next()` returns its type argument, which erases to `Object`; the analyzer has
     * already checked the loop variable against it, so this only has to find the argument back.
     */
    private fun iterableElementTypeOf(type: ClassType): NekoType? =
        type.arguments.firstOrNull()

    /**
     * `i++` / `i--` — read the variable, add or subtract one, write it back.
     *
     * Constants are folded into a single `iinc` when they can be: `iinc` takes a signed byte, so the
     * `int` case uses it and every other type goes through the general path. Either way the type's own
     * conversion is applied on the way back in, so a `byte` variable does not silently widen to `int`.
     */
    private fun emitIncrement(statement: IncrementStatement, scope: MethodScope) {
        val mv = scope.mv
        // `holder.count++` names a field through a receiver, so the field path is taken even though the
        // resolution here is the field symbol rather than a local.
        val member = statement.memberTarget
        if (member != null) {
            val field = member.resolvedField ?: return
            emitIncrementOfReachedField(statement, member, field, scope)
            return
        }

        val symbol = statement.target.resolution ?: return
        val type = symbol.type

        val slot = when (symbol) {
            is LocalSymbol -> scope.slotOf(symbol) ?: scope.allocate(symbol)
            is FieldSymbol -> {
                emitIncrementOfField(statement, symbol, type, scope)
                return
            }

            else -> return
        }

        val delta = if (statement.operator == "++") 1 else -1
        if (type === IntType) {
            // `iinc` does the read, the add and the store in one instruction, which is what `javac`
            // emits for the same statement.
            mv.visitIincInsn(slot, delta)
            return
        }

        mv.visitVarInsn(loadOpcode(type), slot)
        if (delta > 0) {
            mv.visitInsn(if (type === LongType) LCONST_1 else ICONST_1)
            mv.visitInsn(if (type === LongType) LADD else IADD)
        } else {
            mv.visitInsn(if (type === LongType) LCONST_1 else ICONST_1)
            mv.visitInsn(if (type === LongType) LSUB else ISUB)
        }
        // `byte` and `short` are computed as `int` and narrowed back, exactly as an assignment to them
        // would do; skipping this would store a value the type cannot hold.
        emitStoreValue(if (type === LongType) LongType else IntType, type, mv)
        mv.visitVarInsn(storeOpcode(type), slot)
    }

    /**
     * `this.count++` — the field case, which no local slot can serve.
     *
     * Only an instance field on `this` reaches here: the parser accepts a bare name as the target, and
     * the analyzer resolves it to whichever symbol the name means, so a `static` field or one reached
     * through a receiver is filled in by the field's own access path.
     */
    private fun emitIncrementOfField(
        statement: IncrementStatement,
        field: FieldSymbol,
        type: NekoType,
        scope: MethodScope,
    ) {
        val mv = scope.mv
        val delta = if (statement.operator == "++") 1 else -1
        val owner = field.enclosingClass.jvmInternalName
        val descriptor = descriptorOf(type)

        if (!field.isStatic) mv.visitVarInsn(ALOAD, 0)
        if (field.isStatic) {
            mv.visitFieldInsn(GETSTATIC, owner, field.name, descriptor)
        } else {
            mv.visitInsn(DUP)
            mv.visitFieldInsn(GETFIELD, owner, field.name, descriptor)
        }
        mv.visitInsn(if (type === LongType) LCONST_1 else ICONST_1)
        mv.visitInsn(
            when {
                type === LongType && delta > 0 -> LADD
                type === LongType -> LSUB
                delta > 0 -> IADD
                else -> ISUB
            },
        )
        emitStoreValue(if (type === LongType) LongType else IntType, type, mv)
        if (field.isStatic) {
            mv.visitFieldInsn(PUTSTATIC, owner, field.name, descriptor)
        } else {
            mv.visitFieldInsn(PUTFIELD, owner, field.name, descriptor)
        }
    }

    /**
     * `holder.count++` — the field is reached through a receiver, so the receiver is evaluated once and
     * its reference is kept on the stack under both the read and the write.
     *
     * `getfield`/`putfield` both consume the object reference, so the sequence is
     * `receiver; dup; getfield; <one>; add; putfield`: the `dup` leaves one copy for the read and one
     * for the write, which is what makes the whole increment a single evaluation of the receiver. That
     * matters when the receiver is itself a call — `next().count++` must not call `next()` twice.
     */
    private fun emitIncrementOfReachedField(
        statement: IncrementStatement,
        member: MemberAccessExpression,
        field: FieldSymbol,
        scope: MethodScope,
    ) {
        val mv = scope.mv
        val type = field.type
        val delta = if (statement.operator == "++") 1 else -1
        val owner = field.enclosingClass.jvmInternalName
        val descriptor = descriptorOf(type)

        if (field.isStatic) {
            mv.visitFieldInsn(GETSTATIC, owner, field.name, descriptor)
        } else {
            emitExpression(member.receiver, scope)
            mv.visitInsn(DUP)
            mv.visitFieldInsn(GETFIELD, owner, field.name, descriptor)
        }
        mv.visitInsn(if (type === LongType) LCONST_1 else ICONST_1)
        mv.visitInsn(
            when {
                type === LongType && delta > 0 -> LADD
                type === LongType -> LSUB
                delta > 0 -> IADD
                else -> ISUB
            },
        )
        emitStoreValue(if (type === LongType) LongType else IntType, type, mv)
        if (field.isStatic) {
            mv.visitFieldInsn(PUTSTATIC, owner, field.name, descriptor)
        } else {
            mv.visitFieldInsn(PUTFIELD, owner, field.name, descriptor)
        }
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
     * `scratch 1 * multiplier, multiplier` — calls the handler this function was given.
     *
     * A `scratch` is an ordinary call through a *value*. The function's `emit` clause makes it take a
     * hidden `ScratchHandlerN` as its last parameter, so this loads that local and invokes
     * `dispatch` on it with the clause's values. Three things follow, and each was impossible when
     * a handler was a `static` method found by signature:
     *
     *  * **The caller decides what runs.** The same callee serves every `try` that calls it, each
     *    supplying its own handler object, so two `try` blocks can no longer collide.
     *  * **The values are boxed.** `dispatch` takes `Object`s, so each value is boxed here; that is
     *    what lets one interface serve every `emit` clause of the same arity.
     *  * **The answer is unboxed.** `dispatch` returns an `Object`, and the type this site wants is
     *    whatever its position asked for, so the conversion happens here — which is why one handler
     *    can answer an `int` to one site and a `string` to another.
     *
     * A handler that never `nyan`s, or `nyan`s nothing, answers `null`. Where this site wants a
     * primitive that would otherwise be an unboxing `NullPointerException`, so the null is turned
     * into the type's Java default instead: a `scratch` whose handler says nothing is a zero, not a
     * crash.
     */
    private fun emitScratch(expression: ScratchExpression, scope: MethodScope) {
        val mv = scope.mv
        val owner = scope.handlerInterface
        val slot = scope.handlerSlot
        val field = scope.handlerField

        if (owner == null || (slot == null && field == null)) {
            // No handler to call: the site is outside any `emit` function, which analysis has already
            // reported. A default of the right shape keeps the rest of the expression verifiable.
            pushDefault(expression.type, mv)
            return
        }

        if (slot != null) {
            mv.visitVarInsn(ALOAD, slot)
        } else {
            // The handler was captured by the class this body was compiled into.
            mv.visitVarInsn(ALOAD, 0)
            mv.visitFieldInsn(GETFIELD, scope.owner, field!!, descriptorOf(ClassType(owner, owner.substringAfterLast('/'))))
        }
        for (value in expression.values) {
            emitExpression(value, scope)
            emitBox(value.type, mv)
        }
        emitSignalArgument(expression.signal, scope)
        mv.visitMethodInsn(
            INVOKEINTERFACE,
            owner,
            DISPATCH_METHOD_NAME,
            descriptorOf(
                List(expression.values.size) { ObjectType } + ClassType(ENUM_CLASS, "Enum"),
                ObjectType,
            ),
            true,
        )
        emitScratchAnswer(expression.type, scope)
    }

    /**
     * Pushes the `Enum` that says which `blep` branch a `scratch` is asking for.
     *
     * A `scratch` need not name a signal. With none, the value pushed is `null`, which no branch can
     * match —`if (signal == NekoSignal.ADD)` is a comparison against a constant, and a `null` signal
     * equals none of them —so the group answers with its default body. That is what makes `signal`
     * purely additive: an existing `scratch` written without one keeps behaving exactly as it did,
     * and a group whose body *is* the default answer serves both kinds of site.
     */
    private fun emitSignalArgument(signal: Expression?, scope: MethodScope) {
        if (signal == null) {
            scope.mv.visitInsn(ACONST_NULL)
            return
        }
        emitExpression(signal, scope)
    }

    /**
     * Converts `dispatch`'s `Object` answer into the type the `scratch` site wants.
     *
     * Three shapes, and the middle one is the reason this is not one instruction:
     *
     *  * **a reference type** is a `checkcast`, which is what makes the answer usable at all. A `null`
     *    answer casts happily, so a site that wants an object simply gets nothing.
     *  * **a primitive** cannot be `null` in the JVM, so the answer is tested first and the type's
     *    default is substituted when the handler said nothing. A handler that `nyan`s no value is then
     *    a zero rather than an unboxing `NullPointerException` — which is what "use the Java default
     *    when there is no value" means in practice.
     *  * **a discarded site** pops, because nothing reads it.
     */
    private fun emitScratchAnswer(type: NekoType, scope: MethodScope) {
        val mv = scope.mv
        if (type == VoidType || type === ErrorType) {
            mv.visitInsn(POP)
            if (type === ErrorType) mv.visitInsn(ACONST_NULL)
            return
        }

        if (BuiltinTypes.isPrimitive(type)) {
            // The answer is needed twice, so it goes into a local of its own. `Object` is its type
            // here: the unboxing happens only after the null test has said it is safe.
            val slot = scope.allocateTemp(ObjectType)
            val answered = Label()
            val done = Label()
            mv.visitVarInsn(ASTORE, slot)
            mv.visitVarInsn(ALOAD, slot)
            mv.visitJumpInsn(IFNONNULL, answered)
            pushDefault(type, mv)
            mv.visitJumpInsn(GOTO, done)
            mv.visitLabel(answered)
            mv.visitVarInsn(ALOAD, slot)
            emitUnbox(type, mv)
            mv.visitLabel(done)
            return
        }

        when (type) {
            // `string` is its own type here, and `emitUnbox` already casts it.
            StringType -> emitUnbox(type, mv)
            is ArrayType -> mv.visitTypeInsn(CHECKCAST, descriptorOf(type))
            is ClassType -> if (type.internalName != "java/lang/Object") {
                mv.visitTypeInsn(CHECKCAST, type.internalName)
            }
            // `int?` and friends: the erased form is the wrapper, so the cast is to that.
            is NullableType -> when (val inner = type.unwrapNullable()) {
                is ClassType -> mv.visitTypeInsn(CHECKCAST, inner.internalName)
                else -> BuiltinTypes.boxedInternalNameOf(inner)?.let { mv.visitTypeInsn(CHECKCAST, it) }
            }
            else -> Unit
        }
    }



    /**
     * Whether this body can let an `EmitYieldException` out, so the method should declare it.
     *
     * Two things make that possible, and both have to be counted:
     *
     *  * a `yield` written in this body and not swallowed by a `try` of its own — the throw is emitted
     *    right here;
     *  * a call to any function that can do either, since the exception simply passes through.
     *
     * The second is not an optimization: without it, `fun run() { v = try inner() { ... } }` would be
     * emitted without the attribute while `inner` throws, and the class file would describe the wrong
     * thing. A `try`'s own handler is excluded from the first because its `yield` answers *that* `try`
     * and is caught before it could leave.
     */
    private fun BlockStatement.reachesYield(): Boolean = statements.any { it.reachesYield() }

    private fun Statement.reachesYield(): Boolean = when (this) {
        is YieldStatement -> true
        is BlockStatement -> reachesYield()
        is IfStatement -> thenBranch.reachesYield() || elseBranch?.reachesYield() == true
        is WhenStatement -> branches.any { it.body.reachesYield() }
        is WhileStatement -> body.reachesYield()
        is ForStatement -> body.reachesYield() || update?.reachesYield() == true
        is ForEachStatement -> body.reachesYield()
        is PurrStatement -> body.reachesYield()
        is ExpressionStatement -> expression.reachesYield()
        is VariableDeclarationStatement -> initializer?.reachesYield() == true
        is ReturnStatement -> value?.reachesYield() == true
        is AssignmentStatement -> value.reachesYield()
        is IndexAssignmentStatement -> value.reachesYield()
        // The call inside the `try` may throw, and this method catches it — so it does *not* let the
        // exception out, which is exactly why the attribute is not wanted here. The handler body is a
        // method of its own, so what it does is not this method's business either.
        is TryStatement -> false
        // This form catches `Exception` and rethrows a `yield` untouched, so a `yield` out of the call
        // still leaves this method — but the `scratch` itself cannot produce one.
        is TryScratchStatement -> call.reachesYield()
        else -> false
    }

    /**
     * Whether this expression can let a `yield` out, which for the purposes here means whether it can
     * throw `EmitYieldException`.
     *
     * A call counts when its callee declares an `emit` clause: that clause is precisely the promise
     * that the function may leave by throwing rather than returning.
     */
    private fun Expression.reachesYield(): Boolean = when (this) {
        is CallExpression -> {
            // The callee's own declaration is what says whether it may leave by throwing. A bare name
            // records its target in `resolution`; a member access names it in `resolvedFunction`.
            val target = (callee as? NameExpression)?.resolution as? FunctionSymbol
                ?: (callee as? MemberAccessExpression)?.resolvedFunction
            (target != null && target.canRaise) ||
                arguments.any { it.reachesYield() } ||
                callee.reachesYield()
        }
        is BinaryExpression -> left.reachesYield() || right.reachesYield()
        is UnaryExpression -> operand.reachesYield()
        is NyaExpression ->
            failureBlock?.statements?.any { it.reachesYield() } == true ||
                elseBlock?.statements?.any { it.reachesYield() } == true
        is IndexExpression -> array.reachesYield() || index.reachesYield()
        is MemberAccessExpression -> receiver.reachesYield()
        is ScratchExpression -> values.any { it.reachesYield() }
        is TryScratchExpression -> call.reachesYield() || scratch.reachesYield()
        else -> false
    }


    /**
     * `try test(10) { emit1, emit2 -> ... }` — calls the function and runs the `{ ... }` block if it
     * reaches a handler, discarding whatever that block answers.
     *
     * The `{ ... }` block is not run here: it becomes a `static` method of this class (see
     * [emitHandlerMethod]) and the `try` itself only has to call the function and then catch the
     * `yield`s that come back out of it.
     */
    private fun emitTry(statement: TryStatement, scope: MethodScope) {
        emitExtractorTry(statement, scope, resultSlot = null)
    }

    // ------------------------------------------------------------------- purr

    /**
     * `purr { ... }` 鈥?take a `StampedLock`, run the block, unlock on the way out.
     *
     * This cannot be a library call, and the reason is the *release*. It has to happen on every way
     * out of the block, and there are five: falling off the end, `return`, `nyan`, `yield`, and a
     * thrown exception. The last two are covered by a catch-all handler, since a `yield` is an
     * `athrow` like any other; a `return` and a `nyan` are not covered by anything, which is why the
     * release is also emitted inline before the `*RETURN` instruction 鈥?see `MethodScope.purr` and
     * [emitPurrReleaseOnExit].
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
     * `r: int = try f() scratch 1, 2` — catch a Java exception and answer it with a handler's result.
     *
     * The slot holds a boxed value from either path: `f()`'s return value when it returned, and what
     * the handler `nyan`ed when it threw. Boxing makes one local serve both, whatever their JVM types.
     */
    private fun emitTryScratchExpression(expression: TryScratchExpression, scope: MethodScope) {
        val mv = scope.mv
        val resultSlot = scope.allocateTemp(ObjectType)
        emitTryScratch(expression, scope, resultSlot)

        mv.visitVarInsn(ALOAD, resultSlot)
        val type = expression.type
        if (type == VoidType || type === ErrorType) {
            // Analysis rejected this; a null of the erased type keeps the consumer verifiable.
            mv.visitInsn(POP)
            mv.visitInsn(ACONST_NULL)
        } else {
            emitUnbox(type, mv)
        }
    }

    /**
     * The `try`/`catch` both `try call() scratch ...` forms share.
     *
     * This is the mirror image of [emitExtractorTry]: there the `catch` exists to receive a `yield`
     * from a handler *this* `try` declared, while here it exists to receive a **Java exception** the
     * call threw, and the handler it then enters was declared by some other `try` entirely. So the
     * two directions of the same mechanism meet in one place:
     *
     *  * a `yield` escaping the call is **rethrown untouched**. It is not a failure — it is the effect
     *    mechanism travelling to whichever `try` declared the handler it belongs to — and catching it
     *    here would silently turn another construct's answer into this one's failure. `EmitYieldException`
     *    is an `Exception`, so without this test the catch-all below would swallow it.
     *  * everything else is the failure this construct is for, so the `scratch` runs and its handler's
     *    `nyan` answer becomes the construct's value. That answer arrives here rather than at the
     *    `try` that declared the handler, because *this* site is the one that entered it.
     *
     * [resultSlot] collects the value — the call's return value on the normal path, the handler's
     * answer on the failure path — or is `null` when the construct is a statement and discards both.
     */
    private fun emitTryScratch(construct: TryScratch, scope: MethodScope, resultSlot: Int?) {
        val mv = scope.mv
        val exceptionClass = emitYieldExceptionClass()

        val start = Label()
        val end = Label()
        val caught = Label()
        val after = Label()

        // `java.lang.Exception` rather than `Throwable`: a failure the program can handle is an
        // `Exception`, while an `Error` such as `OutOfMemoryError` is not something a handler should
        // be allowed to swallow and carry on from.
        //
        // The slot holding the caught object is allocated *before* the protected range opens, exactly
        // as the `yield`-catching form does: a slot first introduced inside the `try` range has no
        // stack-map entry at the catch label, and ASM then fails to merge the frames.
        val thrownSlot = scope.allocateTemp(ThrowableType)

        mv.visitTryCatchBlock(start, end, caught, "java/lang/Exception")

        mv.visitLabel(start)
        emitExpression(construct.call, scope)
        if (resultSlot == null) {
            popIfValue(construct.call.type, mv)
        } else {
            if (construct.call.type != VoidType && construct.call.type !== ErrorType) {
                emitBox(construct.call.type, mv)
            } else {
                // Analysis reported a `void` call used as a value; a null keeps the method verifiable.
                mv.visitInsn(ACONST_NULL)
            }
            mv.visitVarInsn(ASTORE, resultSlot)
        }
        mv.visitLabel(end)
        mv.visitJumpInsn(GOTO, after)

        mv.visitLabel(caught)
        mv.visitVarInsn(ASTORE, thrownSlot)
        markLine(mv, construct.scratch)

        // A `yield` on its way out is not this construct's failure — rethrow it so it reaches the
        // `try` that declared the handler it belongs to. `EmitYieldException` is a checked
        // `Exception`, so without this the catch-all below would swallow another construct's answer
        // and turn it into this one's failure.
        //
        // The test is written as two separate `if`s rather than an `INSTANCEOF`/`IFEQ` pair over one
        // `ALOAD`, because every branch here has to leave the operand stack empty: the handler body
        // that follows may be a `RETURN`, and a leftover value on the stack would make the frame
        // merge fail when ASM computes the stack map.
        mv.visitVarInsn(ALOAD, thrownSlot)
        mv.visitTypeInsn(INSTANCEOF, exceptionClass)
        val notYield = Label()
        mv.visitJumpInsn(IFEQ, notYield)
        mv.visitVarInsn(ALOAD, thrownSlot)
        mv.visitTypeInsn(CHECKCAST, exceptionClass)
        mv.visitInsn(ATHROW)
        mv.visitLabel(notYield)

        // The exception object itself is deliberately not passed on: the construct's contract is that
        // the `scratch` values are what the handler receives, so `try f() scratch 1, 2` sends exactly
        // the two values the author wrote.
        //
        // What `emitScratch` leaves on the stack is already converted to the type the site wanted, so
        // `scratch.type` — not a handler's return type — is what decides whether there is anything to
        // store. A `ScratchExpression` written as a statement has no type of its own and pushes
        // nothing here, which is why the pop and the store are chosen from the same value.
        emitScratch(construct.scratch, scope)
        val answered = construct.scratch.type
        if (resultSlot == null) {
            popIfValue(answered, mv)
        } else {
            if (answered != VoidType && answered !== ErrorType) {
                emitBox(answered, mv)
            } else {
                mv.visitInsn(ACONST_NULL)
            }
            mv.visitVarInsn(ASTORE, resultSlot)
        }
        mv.visitLabel(after)
    }

    /**
     * `r: int = try f() { a -> nyan a + 1 }` — the same construct, but worth a value.
     *
     * Both paths meet at one slot: the call's own return value when it returns, and the value the
     * handler answered when it ran instead. The handler has to answer a value for this form to mean
     * anything, so the `scratch` sites reaching it must all be initialising something — the analyzer
     * requires that, and here the slot is simply filled from whichever path was taken and converted
     * into the `try`'s own type.
     *
     * The boxed slot is still used because the two paths can disagree on the JVM type: the call
     * answers whatever it returns, and a `nyan` in the handler answers what the sites asked for.
     * Boxing both makes one local serve either, which the verifier accepts.
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
     * The handler is a method, so the only thing the `try` itself has to do is catch the `yield`
     * exceptions the call can let out: a `yield` inside the handler throws `EmitYieldException`
     * carrying its value, and the `try` that declared the handler is what catches it. A `scratch`,
     * by contrast, is an ordinary call and never reaches here.
     *
     * [resultSlot] is the local that collects the construct's value — the call's return value on the
     * normal path, the exception's payload on the `yield` path — or `null` when the value is
     * discarded.
     */
    private fun emitExtractorTry(construct: ExtractorTry, scope: MethodScope, resultSlot: Int?) {
        val mv = scope.mv
        val exceptionClass = emitYieldExceptionClass()

        // Everything the body reads from an enclosing method travels in as a field, exactly as it does
        // for a lambda. A symbol stamped with this construct's own identity is a local *of the
        // handler*, so it needs no capture; anything else must be reachable from here to be captured
        // at all — a nested handler forwards whatever it needs, because its own scope records it.
        val referenced = LinkedHashSet<LocalSymbol>()
        collectLocalReferences(construct.extractorBody, referenced)
        val captures = mutableListOf<Pair<LocalSymbol, String>>()
        for (symbol in referenced) {
            if (symbol.enclosingLambda === construct) continue
            if (scope.slotOf(symbol) == null && scope.captureOf(symbol) == null) continue
            captures += symbol to "captured\$${captures.size}"
        }

        // The handler is a *value* now, so this `try` builds one before it calls: a fresh object of a
        // class generated for this `try` alone. That is the whole difference from the old design —
        // the handler travels with the call instead of being found by signature, which is what lets
        // several `try` blocks answer the same `emit` clause.
        // A `scratch` written inside this handler's own body scratches the effect further out, so the
        // handler the enclosing function holds travels in as well — otherwise that site would have no
        // object to call and would silently produce the type's default.
        val outerHandlerInterface = scope.handlerInterface
        val outerHandlerSlot = scope.handlerSlot
        val outerHandlerField = scope.handlerField

        val handlerClass = emitScratchHandlerClass(construct, captures, outerHandlerInterface)
        val handlerSlot = scope.allocateTemp(ObjectType)
        mv.visitTypeInsn(NEW, handlerClass)
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
        if (outerHandlerInterface != null) {
            if (outerHandlerSlot != null) {
                mv.visitVarInsn(ALOAD, outerHandlerSlot)
            } else {
                mv.visitVarInsn(ALOAD, 0)
                mv.visitFieldInsn(
                    GETFIELD,
                    scope.owner,
                    outerHandlerField ?: "outerHandler",
                    "L$outerHandlerInterface;",
                )
            }
        }
        val constructorTypes = captures.map { it.first.type } +
            listOfNotNull(outerHandlerInterface?.let { ClassType(it, it.substringAfterLast('/')) })
        mv.visitMethodInsn(
            INVOKESPECIAL,
            handlerClass,
            "<init>",
            descriptorOf(constructorTypes, VoidType),
            false,
        )
        mv.visitVarInsn(ASTORE, handlerSlot)

        val start = Label()
        val end = Label()
        val handler = Label()
        val after = Label()

        // The exception object is kept so the value it carries can be read out of it.
        val exceptionSlot = scope.allocateTemp(ThrowableType)

        mv.visitTryCatchBlock(start, end, handler, exceptionClass)

        mv.visitLabel(start)
        // Registered against this exact call, so a nested call among its arguments cannot take it.
        tryHandlers[construct.call] = handlerSlot
        emitExpression(construct.call, scope)
        tryHandlers.remove(construct.call)
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

        // A `yield` anywhere below this call — in the handler, or in a function it called — arrives
        // here. Its `data` field *is* the value, so there is nothing to unpack.
        mv.visitLabel(handler)
        mv.visitVarInsn(ASTORE, exceptionSlot)
        markLine(mv, construct.extractorBody)
        mv.visitVarInsn(ALOAD, exceptionSlot)
        mv.visitFieldInsn(
            GETFIELD,
            exceptionClass,
            EMIT_YIELD_PAYLOAD_FIELD,
            "Ljava/lang/Object;",
        )
        if (resultSlot == null) {
            mv.visitInsn(POP)
        } else {
            // The payload is already an `Object`, which is exactly what [resultSlot] holds, so the
            // two paths of the `try` meet in one local without a conversion.
            mv.visitVarInsn(ASTORE, resultSlot)
        }
        mv.visitLabel(after)
    }

    /**
     * Emits the class that implements one `try`'s handler, and returns its internal name.
     *
     * The `{ ... }` block becomes `dispatch` on a class of its own, which implements the
     * `ScratchHandlerN` the wrapped call's `emit` clause calls for — the same shape a lambda takes,
     * for the same reason: the block is a method of a generated object.
     *
     * Four details are load-bearing:
     *
     *  * **It captures what it reads.** A handler runs at a moment of its own, from a frame of its
     *    own, so an enclosing local it names is copied into a field and handed in through the
     *    constructor. This is what makes reading one legal; before, the block was a `static` method
     *    and had no way to reach it, which the analyzer did not report.
     *  * **The interface's parameters are `Object`s.** So each one is converted to the type the
     *    clause declared, into a local of its own, before the body runs. That is what lets a body
     *    keep writing `emit1 * emit2` against `int` while the JVM sees `Object`.
     *  * **The method answers `Object`.** A `nyan` boxes its value on the way out and a bare one (or
     *    no `nyan` at all) answers `null`; each `scratch` site converts that to the type it wants, so
     *    one handler can serve sites wanting different types.
     *  * **`yield` still throws.** It leaves through the `try`, which catches `EmitYieldException`,
     *    rather than returning from this method — the two exits mean different things and both are
     *    needed.
     */
    private fun emitScratchHandlerClass(
        construct: ExtractorTry,
        captures: List<Pair<LocalSymbol, String>>,
        outerHandlerInterface: String?,
    ): String {
        val name = "${currentOwner}\$ScratchHandler\$${scratchHandlerCounter++}"
        val parameters = construct.extractorParameters
        val signature = construct.handler.signature
        val interfaceName = standardLibraryClass("ScratchHandler${signature.size}")
        // A body that scratches again needs the handler *it* was given, so that one travels in like
        // any other captured value. It is named apart from the local captures so the two can never
        // be confused in a diagnostic.
        val outerHandlerField = outerHandlerInterface?.let { "outerHandler" }

        val enclosingOwner = currentOwner
        currentOwner = name

        val writer = NekoClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(
            V1_8,
            ACC_FINAL or ACC_SUPER,
            name,
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

        if (outerHandlerField != null && outerHandlerInterface != null) {
            writer.visitField(
                ACC_PRIVATE or ACC_FINAL,
                outerHandlerField,
                "L$outerHandlerInterface;",
                null,
                null,
            ).visitEnd()
        }

        emitHandlerConstructor(
            writer,
            name,
            captures,
            outerHandlerField?.let { it to outerHandlerInterface!! },
        )

        val mv = writer.visitMethod(
            ACC_PUBLIC,
            DISPATCH_METHOD_NAME,
            descriptorOf(
                List(signature.size) { ObjectType } + ClassType(ENUM_CLASS, "Enum"),
                ObjectType,
            ),
            null,
            // The body may `yield`, which throws — the JVM does not verify checked exceptions, and
            // listing one would only make the class noisier than the source it came from.
            null,
        )
        mv.visitCode()

        val scope = MethodScope(
            owner = name,
            mv = mv,
            lambdaIdentity = construct,
            // `this` occupies slot 0 and the interface's `Object` parameters follow.
            firstSlot = 1,
            // The captured fields are how an enclosing local is read from here.
            captures = captures.associate { it.first to it.second },
            static = false,
            // What a `nyan` returns from, and what the trailing return has to agree with.
            returnType = ObjectType,
            extractorResult = true,
            // A body that scratches again calls the handler *it* was handed, which is a field here.
            handlerInterface = outerHandlerInterface,
            handlerField = outerHandlerField,
        )
        // The `Object`s the interface handed over occupy the first slots *and* the signal occupies the
        // last of them, so the locals this method makes start after the signal —not after the values,
        // which would put a converted `int` on top of the `Enum` the branches compare against.
        scope.advancePast(1 + signature.size + 1)

        // The interface hands over `Object`s; the clause says what they are. They are converted once,
        // into locals the branches and the `else` body share —every `blep` of a group answers the
        // same clause, so they all read the same values, which is why one conversion serves them all.
        val argumentSlots = IntArray(signature.size)
        for (index in signature.indices) {
            val type = signature[index]
            val slot = scope.allocateTemp(type)
            argumentSlots[index] = slot
            mv.visitVarInsn(ALOAD, 1 + index)
            emitUnbox(type, mv)
            mv.visitVarInsn(storeOpcode(type), slot)
        }

        parameters.forEachIndexed { index, parameter ->
            val symbol = parameter.symbol ?: return@forEachIndexed
            val slot = argumentSlots.getOrNull(index) ?: return@forEachIndexed
            scope.reserve(symbol, slot)
        }

        // The signal is the last parameter the interface declares, and every `blep` branch is a test
        // on it. It is read from its slot at each test rather than kept on the stack, because the body
        // that follows a match may itself scratch.
        emitScratchHandlerBranches(construct, scope, signalSlot = 1 + signature.size, argumentSlots)

        for (inner in construct.extractorBody.statements) {
            emitStatement(inner, scope)
        }

        // A body that says nothing, or whose `nyan` is inside a branch, reaches here. Answering
        // `null` is the documented behaviour for "no value", and a `scratch` wanting a primitive
        // turns it into that type's default.
        mv.visitInsn(ACONST_NULL)
        mv.visitInsn(ARETURN)

        mv.visitMaxs(0, 0)
        mv.visitEnd()
        writer.visitEnd()

        output[name] = writer.toByteArray()
        currentOwner = enclosingOwner
        return name
    }

    /**
     * Emits the `if (signal == CONST)` chain a handler group declared, one branch per `blep`.
     *
     * The shape is the point. A group answers several operations with one handler, so its `dispatch`
     * compares the incoming signal against each branch's constant in turn and runs the matching body;
     * whatever matched nothing —including the `null` a `scratch` that named no `signal` passes —falls
     * through to the `else`, which is the installing `try`'s own body, emitted straight after this.
     *
     * Each test jumps past the bodies before it, so exactly one of them can run, and each body ends by
     * answering —which is why no jump is needed after one: a `blep` body that could fall through
     * into the next branch would answer the wrong operation.
     *
     * [argumentSlots] are the locals the interface's `Object`s were converted into, which every branch
     * binds its parameters to. A branch shares them rather than converting again: the clause is the
     * same for the whole group, so the values are too.
     */
    private fun emitScratchHandlerBranches(
        construct: ExtractorTry,
        scope: MethodScope,
        signalSlot: Int,
        argumentSlots: IntArray,
    ) {
        val mv = scope.mv
        val branches = construct.handler.branches
        if (branches.isEmpty()) return

        // One label per branch for "not this signal"; each test jumps to its own, and the bodies are
        // laid out in between, so reaching the end of a body never runs the next one.
        val next = List(branches.size) { Label() }
        for ((index, branch) in branches.withIndex()) {
            mv.visitVarInsn(ALOAD, signalSlot)
            emitExpression(branch.signal, scope)
            // An enum constant is a single instance, so identity is what picks it out —the same
            // reading `==` has for an enum-typed value everywhere else.
            mv.visitJumpInsn(IF_ACMPNE, next[index])
            for ((parameterIndex, parameter) in branch.parameters.withIndex()) {
                val symbol = parameter.symbol ?: continue
                val slot = argumentSlots.getOrNull(parameterIndex) ?: continue
                scope.reserve(symbol, slot)
            }
            for (inner in branch.body.statements) {
                emitStatement(inner, scope)
            }
            // A branch that ran to its end without `nyan` answers nothing, exactly as a handler body
            // that ends without one does. It must still leave, or the next branch's test would run
            // against a signal already known not to match.
            mv.visitInsn(ACONST_NULL)
            mv.visitInsn(ARETURN)
            mv.visitLabel(next[index])
        }
    }

    /**
     * The constructor of a generated handler class: the captured locals, then the outer handler.
     *
     * The parameter list has to match what the call site pushes, in the same order, which is why both
     * are built from the same two lists rather than independently.
     */
    private fun emitHandlerConstructor(
        writer: ClassWriter,
        name: String,
        captures: List<Pair<LocalSymbol, String>>,
        outerHandler: Pair<String, String>?,
    ) {
        val parameterTypes = captures.map { it.first.type } +
            listOfNotNull(outerHandler?.let { ClassType(it.second, it.second.substringAfterLast('/')) })
        val mv = writer.visitMethod(ACC_PUBLIC, "<init>", descriptorOf(parameterTypes, VoidType), null, null)
        mv.visitCode()
        mv.visitVarInsn(ALOAD, 0)
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)

        var slot = 1
        for ((symbol, fieldName) in captures) {
            mv.visitVarInsn(ALOAD, 0)
            mv.visitVarInsn(loadOpcode(symbol.type), slot)
            mv.visitFieldInsn(PUTFIELD, name, fieldName, descriptorOf(symbol.type))
            slot += BuiltinTypes.slotCountOf(symbol.type)
        }
        if (outerHandler != null) {
            mv.visitVarInsn(ALOAD, 0)
            mv.visitVarInsn(ALOAD, slot)
            mv.visitFieldInsn(PUTFIELD, name, outerHandler.first, "L${outerHandler.second};")
        }

        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * Boxes a primitive already on the stack into its Java reference type; reference types are left
     * alone.
     *
     * Every primitive gets a `valueOf`, including `byte`, `short` and `char`, whose wrappers have had
     * one since Java 5: a value that has to be an `Object` — the payload a `yield` throws, or the
     * `collect`ed slot a `try` expression meets at — is boxed here, and unboxed to whatever the `emit`
     * clause or the `try`'s own type declared.
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
     * a `yield`'s boxed payload or an erased type parameter, so its static type is `Object` and only
     * the declared type says what it really is. A `ClassCastException` from a mismatch is the right
     * answer 鈥?the alternative is a silently wrong value.
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
            is TryScratchExpression -> emitTryScratchExpression(expression, scope)
            is ScratchExpression -> emitScratch(expression, scope)
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

        // A static field of a **JDK** class: `System.out`. There is no `FieldSymbol` for it — the
        // program never compiled `java.lang.System` — so the analyzer recorded the owner and the
        // descriptor is taken from the access's own type. That is enough for `getstatic`, which is
        // the whole read.
        val platformOwner = expression.resolvedPlatformFieldOwner
        if (platformOwner != null) {
            mv.visitFieldInsn(
                if (expression.resolvedPlatformFieldIsStatic) GETSTATIC else GETFIELD,
                platformOwner,
                expression.memberName,
                descriptorOf(expression.type),
            )
            return
        }

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

        // Concatenation builds a `StringBuilder` and appends each operand with the overload its own
        // type needs. `String.concat` would not do: it takes a `String`, and the whole point of `+`
        // beside a string is that the other side may be a number, a `bool` or a class instance.
        //
        // Both operands are appended, including the one that *is* a `string` — `append(String)` is
        // exactly as cheap as starting from it, and keeping the two sides symmetric is what lets the
        // same code handle `"a" + 1` and `1 + "a"`. Since the analyzer only marks the expression a
        // `string` when one side is, at least one `append` here always appends a `String`.
        if (operator == BinaryOperator.ADD && expression.type == StringType) {
            emitConcatenation(expression, scope)
            return
        }

        emitExpression(expression.left, scope)
        emitExpression(expression.right, scope)

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
                    emitHandlerArgument(expression, instanceMethod, scope)
                    // A method declared in an interface must be dispatched with `invokeinterface`; the
                    // JVM treats using `invokevirtual` on one as a verification error.
                    val opcode = if (instanceMethod.ownerIsInterface) INVOKEINTERFACE else INVOKEVIRTUAL
                    mv.visitMethodInsn(
                        opcode,
                        instanceMethod.jvmOwner,
                        instanceMethod.name,
                        descriptorOf(instanceMethod.descriptorParameterTypes, instanceMethod.returnType),
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
                emitHandlerArgument(expression, instanceMethod, scope)
                // A JDK method's descriptor comes from reflection rather than from the symbol: the
                // analyzer recorded the exact descriptor, and rebuilding it from Neko types could
                // differ in a way the JVM would reject at run time (`Object` versus a parameter's real
                // class, say). A Neko declaration has no such field and is described as before.
                val descriptor = instanceMethod.builtin?.jvmDescriptor
                    ?: descriptorOf(instanceMethod.descriptorParameterTypes, instanceMethod.returnType)
                mv.visitMethodInsn(
                    INVOKESTATIC,
                    instanceMethod.jvmOwner,
                    instanceMethod.name,
                    descriptor,
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
            emitHandlerArgument(expression, symbol, scope)
            mv.visitMethodInsn(
                INVOKESTATIC,
                symbol.jvmOwner,
                symbol.name,
                descriptorOf(symbol.descriptorParameterTypes, symbol.returnType),
                false,
            )
            return
        }

        mv.visitVarInsn(ALOAD, 0)
        emitArguments(expression.arguments, symbol.parameterTypes, scope)
        emitHandlerArgument(expression, symbol, scope)
        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            symbol.jvmOwner,
            symbol.name,
            descriptorOf(symbol.descriptorParameterTypes, symbol.returnType),
            false,
        )
    }

    /**
     * Pushes the handler value a call to [symbol] must carry as its last argument.
     *
     * A function with an `emit` clause takes a `ScratchHandlerN` the source never writes, so every
     * call has to supply one. There are exactly two sources, and the map distinguishes them by the
     * call itself rather than by position — which is what keeps `try f(g())` from giving `g` the
     * handler that belongs to `f`:
     *
     *  * **A `try` that wraps this very call** registered the handler object it built. Take it, and
     *    forget it: it belongs to this one call.
     *  * **A function that forwards its clause** has a handler of its own, in [MethodScope.handlerSlot],
     *    and passing that on is exactly what forwarding means.
     *
     * Anything else is a call analysis already refused, so a `null` is pushed to keep the frame
     * verifiable rather than to pretend the call makes sense.
     */
    private fun emitHandlerArgument(
        expression: CallExpression,
        symbol: FunctionSymbol,
        scope: MethodScope,
    ) {
        if (symbol.handlerType == null) return
        val mv = scope.mv
        val established = tryHandlers.remove(expression)
        if (established != null) {
            mv.visitVarInsn(ALOAD, established)
            return
        }
        val forwarded = scope.handlerSlot
        if (forwarded != null) {
            mv.visitVarInsn(ALOAD, forwarded)
            return
        }
        mv.visitInsn(ACONST_NULL)
    }

    /**
     * The handler objects `try` expressions have built, keyed by the call they belong to.
     *
     * A call cannot be recognised by "is a `try` being emitted" because the call's own arguments may
     * contain further calls; keying by the exact [CallExpression] answers the question for the one
     * call that asked.
     */
    private val tryHandlers = mutableMapOf<CallExpression, Int>()

    // ---------------------------------------------------------------- lambdas

    /**
     * Emits a lambda literal.
     *
     * The literal becomes `new <Owner>$Lambda$n`, with each captured variable copied in through the
     * constructor, so the resulting value is an instance of the function type's interface.
     */
    private fun emitLambda(expression: LambdaExpression, scope: MethodScope) {
        // A lambda written against a *single-method interface* implements that interface directly:
        // the class extends nothing and implements `Runnable` (or whatever was named), and the one
        // abstract method becomes a real method with that name. A lambda written against a function
        // type keeps its old shape — a generated interface with an `invoke` — because there is no
        // user-named type to implement.
        val sam = expression.samInterface
        val functionType = expression.type as? FunctionType
        if (sam == null && functionType == null) {
            diagnostics.error(
                "internal error: a lambda reached code generation without a resolved function type",
                expression.span,
            )
            scope.mv.visitInsn(ACONST_NULL)
            return
        }

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
        if (sam != null) {
            emitSamLambdaClass(lambdaClassName, sam, expression, captures, identity)
        } else {
            emitLambdaClass(
                lambdaClassName,
                functionInterfaceName(functionType!!),
                functionType,
                expression,
                captures,
                identity,
            )
        }
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
    /**
     * Emits a lambda written against a single-method interface as a class implementing it.
     *
     * The difference from [emitLambdaClass] is what the generated method is called and what it
     * declares. There, the method is always `invoke` on a synthetic interface; here it is the
     * interface's own abstract method — `run` for `Runnable`, `compare` for `Comparator` — with that
     * method's signature. Getting either wrong produces a class the JVM rejects as abstract, so both
     * come from [SamTarget] rather than from a guess.
     *
     * The method must be `public`: an interface's methods are public, and the JVM refuses a class
     * whose implementation is narrower.
     */
    private fun emitSamLambdaClass(
        lambdaClassName: String,
        sam: SamTarget,
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
            arrayOf(sam.jvmName),
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
            sam.methodName,
            descriptorOf(sam.parameterTypes, sam.returnType),
            null,
            // No `Exceptions` attribute, matching the synthetic-interface path: a `yield` inside the
            // body still throws, and the JVM does not verify checked exceptions, so listing one would
            // only make the class noisier than the source it came from.
            null,
        )
        mv.visitCode()

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
        emitDefaultReturn(sam.returnType, mv)

        mv.visitMaxs(0, 0)
        mv.visitEnd()
        writer.visitEnd()

        output[lambdaClassName] = writer.toByteArray()
    }

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
            is YieldStatement -> node.value?.let { collectLocalReferences(it, into) }
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

            is ForStatement -> {
                node.initializer?.let { collectLocalReferences(it, into) }
                node.condition?.let { collectLocalReferences(it, into) }
                node.update?.let { collectLocalReferences(it, into) }
                collectLocalReferences(node.body, into)
            }

            is ForEachStatement -> {
                collectLocalReferences(node.iterable, into)
                collectLocalReferences(node.body, into)
            }

            is IncrementStatement -> {
                node.memberTarget?.let { collectLocalReferences(it, into) }
                collectLocalReferences(node.target, into)
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

            // Both halves are emitted inline in this method, so whatever they read from an enclosing
            // scope travels exactly as far as the construct does.
            is TryScratchExpression -> {
                collectLocalReferences(node.call, into)
                collectLocalReferences(node.scratch, into)
            }

            is TryScratchStatement -> {
                collectLocalReferences(node.call, into)
                collectLocalReferences(node.scratch, into)
            }

            // A `purr` block is emitted inline, in this very method, so whatever it reads from an
            // enclosing scope travels exactly as far as the `purr` does.
            is PurrStatement -> collectLocalReferences(node.body, into)

            // A `nyan` and a `scratch` live inside a `try` handler, which is a method of its own, so
            // a value they name has to be captured by that handler. Omitting them here is not a lost
            // optimisation: the capture list is what the constructor is built from, and a reference
            // the list misses is compiled as the type's default instead of as a read.
            is NyanStatement -> node.value?.let { collectLocalReferences(it, into) }
            is ScratchExpression -> node.values.forEach { collectLocalReferences(it, into) }

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
         * The local holding this method's `ScratchHandlerN` parameter, when it takes one.
         *
         * A function with an `emit` clause receives its handler as a hidden last parameter, so its
         * body can reach it by slot. A `scratch` loads this local and calls through it, which is what
         * makes one function's `scratch` reach whichever `try` supplied the value — no name, no
         * static method, and no way for two `try` blocks to be confused with each other.
         *
         * `null` in a method that takes no handler: a plain function, a lambda, or the synthesized
         * `dispatch` method itself.
         */
        var handlerSlot: Int? = null,
        /** The internal name of the interface [handlerSlot] holds, e.g. `.../ScratchHandler2`. */
        var handlerInterface: String? = null,
        /**
         * The field holding the handler this method's *body* was given, when the body is itself a
         * `try` handler.
         *
         * A `scratch` written inside a handler body scratches the effect further out, so the handler
         * that body was handed has to be carried in as a field — it arrives through the constructor
         * rather than occupying a slot of this method's frame, because it is one of the values the
         * generated class captures.
         */
        var handlerField: String? = null,
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

        /**
         * The single method on every `ScratchHandlerN` interface.
         *
         * The interfaces are standard library source — `com.github.kusa233.neko.stdlib.emitter.handler`
         * — so their names are looked up in the program like any other library class; only this method
         * name is fixed here, because it is the contract the code generator calls against.
         */
        const val DISPATCH_METHOD_NAME = "dispatch"

        /**
         * `java.lang.Enum`, the type of the signal that picks a branch of a handler group.
         *
         * Every `dispatch` takes one as its last parameter. A plain `scratch` passes `null`, which no
         * `blep` branch matches, so the group falls through to its default body —which is what makes
         * an ungated `scratch` behave exactly as it did before signals existed.
         */
        const val ENUM_CLASS = "java/lang/Enum"

        /**
         * The field of the `yield` carrier holding the value the `yield` carried.
         *
         * Only the *field* is fixed here. The class it belongs to is looked up in the program like any
         * other standard library class — see [emitYieldExceptionClass] — because the library is Neko
         * source compiled into the program, not a class the compiler ships pre-built.
         */
        const val EMIT_YIELD_PAYLOAD_FIELD = "data"

        /**
         * The JDK error a failed `nya` throws, as an internal name.
         *
         * It belongs to the platform rather than to the Neko standard library, so unlike the `yield`
         * carrier it needs no lookup: `java.lang` is on every JVM that could load the class this code
         * goes into. Using the same error Java's own `assert` throws is what makes a Neko check and a
         * Java one fail in the way a reader already expects.
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

        /**
         * The interface a `for ... in` walks, by JVM internal name.
         *
         * `for (e: T in xs)` over an `Iterable` compiles to `iterator()`, then `hasNext()`/`next()` on
         * the answer — the same three calls Java's own for-each makes, because it is the same
         * interface. Emitting against `java.lang.Iterable` rather than a Neko copy is what makes the
         * protocol interoperable: a Neko class becomes something Java code can walk, and a Neko `for`
         * can walk a `java.util.List` handed over by the platform.
         *
         * The declared descriptor is `()Ljava/util/Iterator;`, so a class implementing this must
         * return exactly that — which is why `Iterator` is a separate constant below.
         */
        const val ITERABLE_INTERFACE = "java/lang/Iterable"

        /** What `iterator()` answers: `java.util.Iterator`, in a package Neko must import. */
        const val ITERATOR_INTERFACE = "java/util/Iterator"
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
