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
 *    an ordinary instance field or method and reached through an object —`obj.f`, or an implicit
 *    `this` from inside another instance member of the same class. Instance field initializers run
 *    in `<init>`, static ones in `<clinit>`. `fun main(args: string[])` is a JVM entry point only
 *    when the source declares it `static`, exactly as the source reads.
 *  * **Values.** `int` →`I`, `bool` →`Z`, `string` →`Ljava/lang/String;`, `T[]` →`[<T>`, and a
 *    function type →a generated interface (see below).
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
 *    answers the `yield` —boxed, since the interface erases `R` to `Object`. Both forms then fill one
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
     * whichever class the `raise` was compiled into —the enclosing Neko class, or a generated
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
     * is a class this very run will emit —its internal name comes from the declaration rather than
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
     * The two families differ only in what `invoke` answers —nothing, or the value the `try`
     * produces —so which one a `try` implements is decided by whether its body yields.
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
     * source can be blamed for —the program never said `final`.
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

        // An interface is `abstract interface`, never `final`, and extends `Object` implicitly —it
        // must not name a superclass. A class is `super`, and extends whatever the source named,
        // defaulting to `Object`. It is `final` only when nothing extends it, because the JVM
        // refuses to load a subclass of a final class.
        val superName = if (declaration.isInterface) {
            "java/lang/Object"
        } else {
            symbol.superclass?.internalName ?: "java/lang/Object"
        }
        val interfaceNames = symbol.interfaces
            .map { it.internalName }
            .takeIf { it.isNotEmpty() }
            ?.toTypedArray()

        val access = when {
            declaration.isInterface -> ACC_PUBLIC or ACC_ABSTRACT or ACC_INTERFACE
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

        val staticFields = declaration.fields.filter { it.isStatic }
        val instanceFields = declaration.fields.filterNot { it.isStatic }
        emitStaticInitializer(writer, staticFields, symbol)
        // Every class gets a constructor, because an instance member is only reachable through an
        // object, and this is where that object's own field initializers run. An interface is never
        // instantiated, so it gets none —the JVM rejects one that has a constructor.
        if (!declaration.isInterface) {
            emitConstructors(writer, declaration, instanceFields, symbol)
        }

        for (function in declaration.functions) {
            val functionSymbol = function.symbol ?: continue
            emitFunction(writer, function, functionSymbol, internalName)
        }

        // A `meow class` derives `equals` and `hashCode` from its fields. They are emitted as raw
        // bytecode rather than lowered from a Neko body because the language cannot express either
        // one: `equals` has to compare a value whose class it only learns at run time, and it has no
        // body to write that in.
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
    ) {
        val initialized = fields.filter { it.initializer != null && it.symbol != null }
        if (initialized.isEmpty()) return

        val mv = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null)
        mv.visitCode()
        val scope = MethodScope(
            owner = symbol.jvmInternalName,
            mv = mv,
            lambdaIdentity = null,
            firstSlot = 0,
            static = true,
        )

        for (field in initialized) {
            val fieldSymbol = field.symbol!!
            val initializer = field.initializer!!
            markLine(mv, initializer)
            emitExpression(initializer, scope)
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
     * The `equals` and `hashCode` a `meow class` gets from its fields.
     *
     * Both are JDK 8 APIs —[java.lang.Object]'s own `equals`/`hashCode`, `getClass`, and
     * `java.util.Objects` —which is what `V1_8` program classes are written against.
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
     * is the null-safe comparison a field holding `null` needs —Neko has no null test to write it
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
            if (field.type == IntType || field.type == BoolType) {
                mv.visitJumpInsn(IF_ICMPNE, notEqual)
            } else {
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
     * A field's own hash is the field for `int`/`bool` and `java.util.Objects.hashCode` otherwise —
     * the same split `equals` makes, which is what keeps the two consistent: instances that compare
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
            if (field.type != IntType && field.type != BoolType) {
                mv.visitMethodInsn(
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
            mv.visitVarInsn(ALOAD, 0)
            emitExpression(initializer, scope)
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
     * `fun main()` —the common case, since the arguments are rarely read and this language has no
     * array literal to build them with —would otherwise compile and then have no entry point at
     * all. Rather than insist on a parameter nobody uses, a bridge is emitted that calls the
     * declared function.
     *
     * The bridge is only for a `static`, argument-less, value-less `main`, which is the shape that
     * means "start here". A non-static `main` is an ordinary instance member and cannot be an entry
     * point, and one that returns a value is not the shape the launcher calls. A `main` that already
     * takes `string[]` is the entry point itself and needs no bridge —emitting one would collide
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

        // An abstract function —one written in an interface with no body —is declared and never
        // emitted, because there is nothing to emit after the signature.
        if (function.isAbstract) {
            access = access or ACC_ABSTRACT
            writer.visitMethod(access, function.name, descriptor, null, null).visitEnd()
            return
        }

        // A function that declares `emit` also declares that it may `raise`, and a `raise` is
        // lowered to throwing `EarlyEmitException` —so that exception is what the class file's
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
            is TryCatchStatement -> emitTryCatch(statement, scope)
            is IndexAssignmentStatement -> emitIndexAssignment(statement, scope)
        }
    }

    private fun emitVariableDeclaration(statement: VariableDeclarationStatement, scope: MethodScope) {
        val symbol = statement.symbol ?: return
        val initializer = statement.initializer
        if (initializer != null) {
            emitExpression(initializer, scope)
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
                scope.mv.visitFieldInsn(
                    PUTSTATIC,
                    symbol.enclosingClass.jvmInternalName,
                    symbol.name,
                    descriptorOf(symbol.type),
                )
            } else {
                // Implicit `this.field = value`, which is the only way a bare name reaches an
                // instance field: the analyzer allows it only inside a non-static member.
                scope.mv.visitVarInsn(ALOAD, 0)
                // The receiver must sit *under* the value, so swap the two on the stack.
                scope.mv.visitInsn(SWAP)
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
            scope.mv.visitInsn(RETURN)
            return
        }
        emitExpression(value, scope)
        scope.mv.visitInsn(returnOpcode(value.type))
    }

    /**
     * `yield x + y` produces the value of the construct it appears in —the lambda's, or the `try`
     * expression's whose extractor it is written in —i.e. it returns from the generated `invoke`.
     *
     * An extractor's `invoke` answers the interface's erased `R`, which is `Object`, so the value is
     * boxed on the way out; a lambda's answers its own return type and needs no boxing.
     */
    private fun emitYield(statement: YieldStatement, scope: MethodScope) {
        emitExpression(statement.value, scope)
        if (scope.extractorResult) {
            emitBox(statement.value.type, scope.mv)
            scope.mv.visitInsn(ARETURN)
            return
        }
        scope.mv.visitInsn(returnOpcode(statement.value.type))
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
        val subjectSlot = scope.allocateTemp()

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
            // Only int and bool subjects are comparable with `==` in Neko.
            if (subjectType == IntType || subjectType == BoolType) {
                mv.visitJumpInsn(IF_ICMPEQ, bodies[index])
            } else {
                mv.visitJumpInsn(IF_ACMPEQ, bodies[index])
            }
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
     * `try { ... } catch (e: T) { ... } finally { ... }` —lowered to the JVM's exception table.
     *
     * The layout is the one `javac` produces for the same source, and the two protected ranges are
     * what make it correct:
     *
     * ```
     *   start:         body                 │explicit `catch` ranges
     *   endBody:       handler_i:  astore e; catchBody
     *   endAll:                              │catch-all range, so an exception thrown *inside* a
     *   finallyNormal: finallyBody; goto after   catch body still reaches the `finally`
     *   catchAll:      astore pending; finallyBody; aload pending; athrow
     *   after:
     * ```
     *
     * The explicit entries are added first and cover only the body, so the JVM —which tests the
     * table in order —matches them exactly as the source reads, and never re-enters a handler that
     * threw. The `finally` body is emitted once per path out of the region, which is the duplication
     * `javac` performs and what makes it run on normal completion, on a caught exception and on an
     * uncaught one alike.
     */
    private fun emitTryCatch(statement: TryCatchStatement, scope: MethodScope) {
        val mv = scope.mv
        val start = Label()
        val endBody = Label()
        val endAll = Label()
        val finallyNormal = Label()
        val catchAll = Label()
        val after = Label()
        val finallyBlock = statement.finallyBlock

        val handlerLabels = statement.catches.map { Label() }
        // A `finally` needs somewhere to park the exception it is running on top of, so that it can
        // rethrow it once its body finishes.
        val pendingSlot = if (finallyBlock != null) scope.allocateTemp() else null

        mv.visitLabel(start)
        emitStatement(statement.body, scope)
        mv.visitLabel(endBody)

        val normalExit = if (finallyBlock != null) finallyNormal else after
        mv.visitJumpInsn(GOTO, normalExit)

        statement.catches.forEachIndexed { index, clause ->
            val type = (clause.resolvedType as? ClassType)?.internalName ?: "java/lang/Throwable"
            mv.visitTryCatchBlock(start, endBody, handlerLabels[index], type)

            mv.visitLabel(handlerLabels[index])
            val symbol = clause.symbol
            if (symbol != null) {
                mv.visitVarInsn(ASTORE, scope.allocate(symbol))
            } else {
                mv.visitInsn(POP)
            }
            emitBlockStatements(clause.body, scope)
            mv.visitJumpInsn(GOTO, normalExit)
        }

        mv.visitLabel(endAll)

        if (finallyBlock != null && pendingSlot != null) {
            mv.visitLabel(finallyNormal)
            emitBlockStatements(finallyBlock, scope)
            mv.visitJumpInsn(GOTO, after)

            // Covers the body *and* every handler, so an exception thrown while handling one still
            // runs the `finally` before it propagates.
            mv.visitTryCatchBlock(start, endAll, catchAll, null)
            mv.visitLabel(catchAll)
            mv.visitVarInsn(ASTORE, pendingSlot)
            emitBlockStatements(finallyBlock, scope)
            mv.visitVarInsn(ALOAD, pendingSlot)
            mv.visitInsn(ATHROW)
        }

        mv.visitLabel(after)
    }

    /** Emits the statements of [block] without opening a scope of its own. */
    private fun emitBlockStatements(block: BlockStatement, scope: MethodScope) {
        markLine(scope.mv, block)
        for (statement in block.statements) emitStatement(statement, scope)
    }

    /** `array[index]` —an `*aload` after the array and the index are on the stack. */
    private fun emitIndex(expression: IndexExpression, scope: MethodScope) {
        val mv = scope.mv
        emitExpression(expression.array, scope)
        emitExpression(expression.index, scope)
        val element = (expression.array.type as? ArrayType)?.element
        mv.visitInsn(arrayLoadOpcode(element ?: ErrorType))
    }

    /** `array[index] = value` —an `*astore` with array, index and value in that order. */
    private fun emitIndexAssignment(statement: IndexAssignmentStatement, scope: MethodScope) {
        val mv = scope.mv
        emitExpression(statement.array, scope)
        emitExpression(statement.index, scope)
        emitExpression(statement.value, scope)
        val element = (statement.array.type as? ArrayType)?.element
        mv.visitInsn(arrayStoreOpcode(element ?: ErrorType))
    }

    /**
     * `raise 1 * multiplier, multiplier` — leaves the function early, handing the values to the
     * caller's extractor.
     *
     * The lowering is the one the sample spells out: the values are boxed into an `Object[]` by a
     * generated static builder —`test$emit$0()` in the sample —then wrapped in the standard
     * library's `EarlyEmitException` and thrown. Boxing is what lets one carrier type serve any
     * number of raised values, and throwing is what lets the caller's `try` catch the hand-off
     * without the callee returning.
     */
    private fun emitRaise(statement: RaiseStatement, scope: MethodScope) {
        val mv = scope.mv

        // The values are boxed by a generated static builder —the sample's `test$emit$0()` —rather
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

        val valuesSlot = scope.allocateTemp()
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
     * This exists because the values have different descriptors —one `int`, one `String` —so no
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

        pushInt(valueTypes.size.toLong(), mv)
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object")

        // The parameters occupy slots 0..n-1, so the array's slot is the one after them.
        val arraySlot = valueTypes.size
        mv.visitVarInsn(ASTORE, arraySlot)

        for ((index, type) in valueTypes.withIndex()) {
            mv.visitVarInsn(ALOAD, arraySlot)
            pushInt(index.toLong(), mv)
            mv.visitVarInsn(loadOpcode(type), index)
            emitBox(type, mv)
            mv.visitInsn(AASTORE)
        }

        mv.visitVarInsn(ALOAD, arraySlot)
        mv.visitInsn(ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * `try test(10) { emit1, emit2 -> ... }` —calls the function and runs the extractor if it
     * raises, discarding whatever the extractor produced.
     *
     * The lowering is a `try`/`catch` around the call, exactly as the sample describes: the call sits
     * in the protected region, and `EarlyEmitException` is caught and handed to the extractor. A call
     * that returns normally jumps past the handler, so the extractor body runs only on a `raise`.
     */
    private fun emitTry(statement: TryStatement, scope: MethodScope) {
        emitExtractorTry(statement, scope, resultSlot = null)
    }

    /**
     * `r: int = try f() { a -> yield 1 }` —the same catching construct, but worth a value.
     *
     * Both paths meet at one slot: the call's own return value when it returns, and `doEmit`'s answer
     * —the value the extractor's `yield` produced —when it `raise`s instead. Which is why the
     * extractor has to yield for this form to mean anything: without one, `doEmit` could only answer
     * `null`, and the reader would silently get nothing. The analyzer requires the yield; here the
     * slot is simply filled from whichever path was taken and unboxed into the `try`'s type.
     */
    private fun emitTryExpression(expression: TryExpression, scope: MethodScope) {
        val mv = scope.mv
        val resultSlot = scope.allocateTemp()
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
     * [resultSlot] is the local that collects the construct's value —the call's return value on the
     * normal path, `doEmit`'s answer on the `raise` path —or `null` when the value is discarded.
     */
    private fun emitExtractorTry(construct: ExtractorTry, scope: MethodScope, resultSlot: Int?) {
        val mv = scope.mv
        val exceptionClass = earlyEmitExceptionClass()

        val start = Label()
        val end = Label()
        val handler = Label()
        val after = Label()

        // The exception object is kept so its array can be handed to the extractor in a moment.
        val exceptionSlot = scope.allocateTemp()

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
     * `EmitExtractorN` for its arity —or `EmitExtractorWithResultN` when the body yields —with
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
     * library supplies `extract(Object[])` as a default method —it unpacks the array and calls
     * `invoke` —so only `invoke` is generated here. Since the interface erases its type parameters
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
            // The interface erases its type parameters, so every value arrives as an `Object` —and a
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

        // Each `Object` parameter is unboxed into the typed local the body expects to read.
        parameters.forEachIndexed { index, parameter ->
            val symbol = parameter.symbol ?: return@forEachIndexed
            val slot = scope.allocate(symbol)
            mv.visitVarInsn(ALOAD, index + 1)
            emitUnbox(parameter.resolvedType ?: ErrorType, mv)
            mv.visitVarInsn(storeOpcode(symbol.type), slot)
        }

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

    /** Boxes an `int`/`bool` already on the stack; reference types are left alone. */
    private fun emitBox(type: NekoType, mv: MethodVisitor) {
        when (type) {
            IntType -> mv.visitMethodInsn(
                INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false,
            )

            BoolType -> mv.visitMethodInsn(
                INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false,
            )

            else -> Unit
        }
    }

    /** Unboxes an `Object` on the stack into [type]; reference types are left alone. */
    private fun emitUnbox(type: NekoType, mv: MethodVisitor) {
        when (type) {
            IntType -> {
                mv.visitTypeInsn(CHECKCAST, "java/lang/Integer")
                mv.visitMethodInsn(
                    INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false,
                )
            }

            BoolType -> {
                mv.visitTypeInsn(CHECKCAST, "java/lang/Boolean")
                mv.visitMethodInsn(
                    INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false,
                )
            }

            StringType -> mv.visitTypeInsn(CHECKCAST, "java/lang/String")
            else -> Unit
        }
    }

    // ----------------------------------------------------------- expressions

    /** Emits [expression], leaving exactly one value on the stack unless its type is void. */
    private fun emitExpression(expression: Expression, scope: MethodScope) {
        when (expression) {
            is IntegerLiteral -> pushInt(expression.value, scope.mv)
            is StringLiteral -> scope.mv.visitLdcInsn(expression.value)
            is BoolLiteral -> scope.mv.visitInsn(if (expression.value) ICONST_1 else ICONST_0)

            is NameExpression -> emitName(expression, scope)
            is ThisExpression -> scope.mv.visitVarInsn(ALOAD, 0)
            is BinaryExpression -> emitBinary(expression, scope)
            is ChainedComparisonExpression -> emitChainedComparison(expression, scope)
            is UnaryExpression -> emitUnary(expression, scope)
            is CallExpression -> emitCall(expression, scope)
            is LambdaExpression -> emitLambda(expression, scope)
            is MemberAccessExpression -> emitMemberAccess(expression, scope)
            is IndexExpression -> emitIndex(expression, scope)
            is TryExpression -> emitTryExpression(expression, scope)
        }
    }

    /**
     * `obj.field` —reads a field through its receiver, or reads a `static` field by class name.
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
            // The receiver of a static field —the class name —is not a value, so nothing is pushed
            // for it; `getstatic` alone is the whole read.
            mv.visitFieldInsn(
                GETSTATIC,
                field.enclosingClass.jvmInternalName,
                field.name,
                descriptorOf(field.type),
            )
            return
        }

        emitExpression(expression.receiver, scope)
        mv.visitFieldInsn(
            GETFIELD,
            field.enclosingClass.jvmInternalName,
            field.name,
            descriptorOf(field.type),
        )
    }

    private fun emitName(expression: NameExpression, scope: MethodScope) {
        when (val symbol = expression.resolution) {
            is LocalSymbol -> {
                val slot = scope.slotOf(symbol)
                if (slot != null) {
                    scope.mv.visitVarInsn(loadOpcode(symbol.type), slot)
                    return
                }
                // Not a local of this method: the lambda must have captured it.
                val capture = scope.captureOf(symbol)
                if (capture != null) {
                    scope.mv.visitVarInsn(ALOAD, 0)
                    scope.mv.visitFieldInsn(GETFIELD, scope.owner, capture, descriptorOf(symbol.type))
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

        when (operator) {
            BinaryOperator.ADD -> mv.visitInsn(IADD)
            BinaryOperator.SUBTRACT -> mv.visitInsn(ISUB)
            BinaryOperator.MULTIPLY -> mv.visitInsn(IMUL)
            BinaryOperator.DIVIDE -> mv.visitInsn(IDIV)
            BinaryOperator.REMAINDER -> mv.visitInsn(IREM)
            BinaryOperator.EQUAL, BinaryOperator.NOT_EQUAL -> emitEquality(expression, scope)
            BinaryOperator.LESS -> emitComparison(scope, IF_ICMPLT)
            BinaryOperator.LESS_EQUAL -> emitComparison(scope, IF_ICMPLE)
            BinaryOperator.GREATER -> emitComparison(scope, IF_ICMPGT)
            BinaryOperator.GREATER_EQUAL -> emitComparison(scope, IF_ICMPGE)
            BinaryOperator.AND, BinaryOperator.OR -> Unit // handled above
        }
    }

    /**
     * `==` on primitives is a numeric compare; on everything else it is `equals`, dispatched
     * virtually on the left operand.
     *
     * The owner is `java/lang/Object` rather than the operand's own class. `invokevirtual` picks the
     * method from the receiver's run-time class, so naming the top of the hierarchy is enough and is
     * always verifiable: the receiver is by definition assignable to `Object`. Naming a narrower
     * owner is not — a `String` receiver was assumed for every reference type here once, which made
     * `a == b` on two plain objects emit a call to `String.equals` on a `Plain`, and the class failed
     * to load with a `VerifyError` instead of comparing anything. Strings still reach
     * `String.equals` through this call; they simply are not the only thing that can.
     */
    private fun emitEquality(expression: BinaryExpression, scope: MethodScope) {
        val mv = scope.mv
        val operandType = expression.left.type
        val wantsEqual = expression.operator == BinaryOperator.EQUAL

        if (operandType == IntType || operandType == BoolType) {
            emitComparison(scope, if (wantsEqual) IF_ICMPEQ else IF_ICMPNE)
            return
        }

        // A `meow class` compares through the `equals` derived from its fields. This dispatch is
        // essential rather than convenient: Neko has no `is` operator and no cast, so without it
        // `a == b` would fall through to the call below and the generated method would be
        // unreachable from any program.
        val meowClass = meowClassOf(operandType)
        val owner = meowClass?.jvmInternalName ?: "java/lang/Object"
        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            owner,
            "equals",
            "(Ljava/lang/Object;)Z",
            false,
        )

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
     * The symbol [type] names when it is a `meow class`, or `null` for everything else.
     *
     * The test is on the class *symbol* rather than on the name: a class that merely happens to be
     * called `Point` has whatever `equals` its author wrote, and dispatching `==` to a derived one
     * would call a method that class file does not have.
     *
     * A meow class held as an `Object` — the one type through which the JVM erases a type parameter —
     * is not recognised here and falls back to `String.equals`. That is unreachable from Neko: a
     * value's type is what its expression resolved to, and nothing in the language can name a class
     * it declares as an `Object`.
     */
    private fun meowClassOf(type: NekoType): ClassSymbol? =
        ((type as? ClassType)?.let { classes[it.simpleName] })?.takeIf { it.isMeow }

    /** Emits `a op b` for the two int operands already on the stack, leaving a boolean. */
    private fun emitComparison(scope: MethodScope, opcode: Int) {
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
        val slots = expression.operands.map { operand ->
            emitExpression(operand, scope)
            val slot = scope.allocateTemp()
            mv.visitVarInsn(ISTORE, slot)
            slot
        }

        val falseLabel = Label()
        val endLabel = Label()

        for (index in expression.operators.indices) {
            mv.visitVarInsn(ILOAD, slots[index])
            mv.visitVarInsn(ILOAD, slots[index + 1])
            // The negated test jumps to `false`, so every comparison must hold to reach `true`.
            mv.visitJumpInsn(negatedComparisonOpcode(expression.operators[index]), falseLabel)
        }

        mv.visitInsn(ICONST_1)
        mv.visitJumpInsn(GOTO, endLabel)
        mv.visitLabel(falseLabel)
        mv.visitInsn(ICONST_0)
        mv.visitLabel(endLabel)
    }

    /** The opcode that jumps when `a op b` is *false*, used to short-circuit a comparison chain. */
    private fun negatedComparisonOpcode(operator: BinaryOperator): Int = when (operator) {
        BinaryOperator.LESS -> IF_ICMPGE
        BinaryOperator.LESS_EQUAL -> IF_ICMPGT
        BinaryOperator.GREATER -> IF_ICMPLE
        BinaryOperator.GREATER_EQUAL -> IF_ICMPLT
        else -> error("'${operator.display}' is not a relational operator")
    }

    private fun emitUnary(expression: UnaryExpression, scope: MethodScope) {
        emitExpression(expression.operand, scope)
        when (expression.operator) {
            UnaryOperator.NEGATE -> scope.mv.visitInsn(INEG)
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
     *  1. a construction, e.g. `CustomException()` →`new` plus `<init>`;
     *  2. a member call, e.g. `obj.f(1)` →`invokevirtual` with the receiver first, or
     *     `Cls.f(1)` →`invokestatic`;
     *  3. a named function →`invokestatic` (a built-in such as `println` first loads its receiver);
     *  4. a function *value*, e.g. the second call in `lambda()(1, 2)` →`invokeinterface`.
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
                emitExpression(receiver, scope)
                for (argument in expression.arguments) emitExpression(argument, scope)
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
            } else {
                for (argument in expression.arguments) emitExpression(argument, scope)
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
        emitExpression(callee, scope)
        for (argument in expression.arguments) emitExpression(argument, scope)

        if (calleeType is FunctionType) {
            mv.visitMethodInsn(
                INVOKEINTERFACE,
                functionInterfaceName(calleeType),
                INVOKE_METHOD_NAME,
                descriptorOf(calleeType.parameters, calleeType.returnType),
                true,
            )
        } else {
            // Analysis already reported that this is not callable.
            popIfValue(calleeType, mv)
        }
    }

    /**
     * Emits `new T(...)` for a construction.
     *
     * The descriptor is the one the resolved constructor declared, not the one the argument types
     * happen to imply: a constructor parameter declared `Object` accepts an `int` argument, which
     * has to be boxed on the way in. A class with no declared constructor —one from outside the
     * program, say —falls back to the arguments' own types, which is what the JVM will require.
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
     * Converts the value on the stack from [from] to [to].
     *
     * Two conversions are possible in Neko: boxing a primitive into the `Object` a type parameter
     * erased to, and boxing it for an `Object` parameter. The reverse —unboxing —is the extractor's
     * job at run time, because only then is the concrete type known.
     */
    private fun emitCoerce(from: NekoType, to: NekoType?, scope: MethodScope) {
        if (to == null) return
        if (to === ObjectType && from !== ObjectType) {
            emitBox(from, scope.mv)
        }
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
            for (argument in expression.arguments) emitExpression(argument, scope)
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
        for (argument in expression.arguments) emitExpression(argument, scope)
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
        IntType -> "I"
        BoolType -> "Z"
        StringType -> "Ljava/lang/String;"
        VoidType -> "V"
        ObjectType -> "Ljava/lang/Object;"
        ThrowableType -> "Ljava/lang/Throwable;"
        is ArrayType -> "[" + descriptorOf(type.element)
        is FunctionType -> "L" + functionInterfaceName(type) + ";"
        is ClassType -> "L" + type.internalName + ";"
        ErrorType -> "Ljava/lang/Object;"
    }

    /** The `*aload` that reads one element of an array of [element]. */
    private fun arrayLoadOpcode(element: NekoType): Int = when (element) {
        IntType -> IALOAD
        BoolType -> BALOAD
        else -> AALOAD
    }

    /** The `*astore` that writes one element of an array of [element]. */
    private fun arrayStoreOpcode(element: NekoType): Int = when (element) {
        IntType -> IASTORE
        BoolType -> BASTORE
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

    private fun loadOpcode(type: NekoType): Int = when (type) {
        IntType, BoolType -> ILOAD
        else -> ALOAD
    }

    private fun storeOpcode(type: NekoType): Int = when (type) {
        IntType, BoolType -> ISTORE
        else -> ASTORE
    }

    private fun returnOpcode(type: NekoType): Int = when (type) {
        VoidType -> RETURN
        IntType, BoolType -> IRETURN
        else -> ARETURN
    }

    private fun popOpcode(type: NekoType): Int = if (type == IntType || type == BoolType) POP else POP

    private fun pushDefaultOpcode(type: NekoType): Int = when (type) {
        IntType, BoolType -> ICONST_0
        else -> ACONST_NULL
    }

    private fun pushDefault(type: NekoType, mv: MethodVisitor) {
        mv.visitInsn(pushDefaultOpcode(type))
    }

    private fun emitDefaultReturn(returnType: NekoType, mv: MethodVisitor) {
        when (returnType) {
            VoidType -> mv.visitInsn(RETURN)
            IntType, BoolType -> {
                mv.visitInsn(ICONST_0)
                mv.visitInsn(IRETURN)
            }

            else -> {
                mv.visitInsn(ACONST_NULL)
                mv.visitInsn(ARETURN)
            }
        }
    }

    private fun popIfValue(type: NekoType, mv: MethodVisitor) {
        if (type != VoidType && type !== ErrorType) mv.visitInsn(POP)
    }

    /** Emits the most compact instruction that pushes [value]. */
    private fun pushInt(value: Long, mv: MethodVisitor) {
        val int = value.toInt()
        when (int) {
            -1 -> mv.visitInsn(ICONST_M1)
            0 -> mv.visitInsn(ICONST_0)
            1 -> mv.visitInsn(ICONST_1)
            2 -> mv.visitInsn(ICONST_2)
            3 -> mv.visitInsn(ICONST_3)
            4 -> mv.visitInsn(ICONST_4)
            5 -> mv.visitInsn(ICONST_5)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(BIPUSH, int)
            in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(SIPUSH, int)
            else -> mv.visitLdcInsn(int)
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

            is TryCatchStatement -> {
                collectLocalReferences(node.body, into)
                for (clause in node.catches) collectLocalReferences(clause.body, into)
                node.finallyBlock?.let { collectLocalReferences(it, into) }
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
            // appears in —so if that method is a lambda, whatever the extractor reads from the
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

            else -> Unit
        }
    }

    /** Per-method state: local variable slots, the owner class, and lambda capture fields. */
    private class MethodScope(
        val owner: String,
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
         * `true` when this method is the `invoke` of a value-returning `try` extractor.
         *
         * A `yield` in such a body produces that `invoke`'s value, i.e. it returns a *boxed* `Object`
         * rather than a value of the enclosing language's type —the interface erases its `R` to
         * `Object`, so the `int` a `yield` names has to be boxed on the way out.
         */
        val extractorResult: Boolean = false,
    ) {
        private var nextSlot = firstSlot
        private val slots = LinkedHashMap<LocalSymbol, Int>()

        /** Slot for [symbol] in this method, or `null` when it is a captured variable. */
        fun slotOf(symbol: LocalSymbol): Int? = slots[symbol]

        /** Capture field name for [symbol] in this lambda, or `null`. */
        fun captureOf(symbol: LocalSymbol): String? = captures[symbol]

        /** Associates [symbol] with the next free slot and returns it. */
        fun allocate(symbol: LocalSymbol): Int {
            val existing = slots[symbol]
            if (existing != null) return existing
            val slot = nextSlot++
            slots[symbol] = slot
            return slot
        }

        /**
         * Reserves a slot for a compiler-generated temporary, such as the subject of a `when`.
         *
         * Temporaries are never reused, so the slot map stays a pure function of the source and the
         * stack-map frames ASM computes always agree with the code emitted.
         */
        fun allocateTemp(): Int = nextSlot++
    }

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
    }
}

/** The name shown in diagnostics and recorded as the class-file source attribute. */
private fun NekoFile?.sourceName(): String =
    this?.span?.file?.name?.substringAfterLast('/')?.substringAfterLast('\\') ?: "unknown.neko"
