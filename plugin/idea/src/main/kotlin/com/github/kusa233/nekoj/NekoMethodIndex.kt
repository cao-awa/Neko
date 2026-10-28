package com.github.kusa233.nekoj

import com.github.kusa233.neko.ast.ArrayTypeReference
import com.github.kusa233.neko.ast.FunctionTypeReference
import com.github.kusa233.neko.ast.NamedTypeReference
import com.github.kusa233.neko.ast.NullableTypeReference
import com.github.kusa233.neko.ast.TypeReference
import com.github.kusa233.neko.source.SourceFile
import com.github.kusa233.neko.source.Span
import com.intellij.openapi.project.Project
import java.lang.module.ModuleFinder
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.TreeMap

/**
 * An indexed method from either a Neko class or the Java platform standard library (`java.base`).
 *
 * Carries the information an IDE needs to offer code completion, symbol search ("Navigate | Symbol"),
 * and navigation: the method's name, declaring class, parameters, return type, and location.
 */
data class IndexedMethod(
    val name: String,
    val ownerSimpleName: String,
    val ownerQualifiedName: String,
    val isStatic: Boolean,
    val parameterTypes: List<String>,
    val parameterNames: List<String> = emptyList(),
    val returnType: String,
    val emitTypes: List<String> = emptyList(),
    val emitResultType: String? = null,
    val isNekoMethod: Boolean,
    val nekoFile: SourceFile? = null,
    val nekoSpan: Span? = null,
    val javaMethod: Method? = null,
) {
    /**
     * Formatted signature, e.g. `toString(long[]): string` or `sleep(millis: long): void`.
     */
    val presentableText: String
        get() = buildString {
            append(name)
            append("(")
            val params = if (parameterNames.size == parameterTypes.size && parameterNames.isNotEmpty()) {
                parameterNames.indices.joinToString(", ") { "${parameterNames[it]}: ${parameterTypes[it]}" }
            } else {
                parameterTypes.joinToString(", ")
            }
            append(params)
            append("): ")
            append(returnType)
            if (emitTypes.isNotEmpty()) {
                append(" emit [")
                append(emitTypes.joinToString(", "))
                append("]")
                emitResultType?.let { append(" -> ").append(it) }
            }
        }

    /**
     * Container description, e.g. `java.util.Arrays (Java Platform)` or `Data in com.github...`.
     */
    val locationString: String
        get() = if (isNekoMethod) {
            val pkg = ownerQualifiedName.substringBeforeLast('.', "")
            if (pkg.isEmpty()) ownerSimpleName else "$ownerSimpleName in $pkg"
        } else {
            "$ownerQualifiedName (Java Platform)"
        }
}

/**
 * Unified method index for Neko classes and the Java standard library.
 *
 * Provides fast queries by method name, class name, and receiver context for both
 * the "Navigate | Symbol" contributor and code completion.
 */
object NekoMethodIndex {

    private const val BASE_MODULE = "java.base"

    /**
     * Java platform declared public methods, indexed by method name.
     * Computed lazily once from `java.base`.
     */
    private val javaMethodsByName: Map<String, List<IndexedMethod>> by lazy {
        readJavaPlatformMethods()
    }

    /**
     * Java platform declared public methods, indexed by fully-qualified class name.
     */
    private val javaMethodsByQualifiedClass: Map<String, List<IndexedMethod>> by lazy {
        val map = LinkedHashMap<String, MutableList<IndexedMethod>>()
        for (methods in javaMethodsByName.values) {
            for (m in methods) {
                map.getOrPut(m.ownerQualifiedName) { mutableListOf() } += m
            }
        }
        map
    }

    /**
     * Java platform declared public methods, indexed by simple class name.
     */
    private val javaMethodsBySimpleClass: Map<String, List<IndexedMethod>> by lazy {
        val map = LinkedHashMap<String, MutableList<IndexedMethod>>()
        for (methods in javaMethodsByName.values) {
            for (m in methods) {
                map.getOrPut(m.ownerSimpleName) { mutableListOf() } += m
            }
        }
        map
    }

    /**
     * Every method declared in or inherited by Neko classes in [project].
     */
    fun nekoMethods(project: Project): List<IndexedMethod> {
        val model = NekoProgramModel.forProject(project)
        val result = mutableListOf<IndexedMethod>()
        val seenSignatures = HashSet<String>()

        for (classSymbol in model.classes.values) {
            val ownerSimple = classSymbol.name
            val ownerQualified = classSymbol.jvmInternalName.replace('/', '.')
            val allFuncs = classSymbol.allFunctions()
            for ((_, overloads) in allFuncs) {
                for (fn in overloads) {
                    val paramTypes = fn.parameterTypes.map { it.displayName }
                    val sig = "$ownerQualified#${fn.name}(${paramTypes.joinToString(",")})"
                    seenSignatures += sig
                    result += IndexedMethod(
                        name = fn.name,
                        ownerSimpleName = ownerSimple,
                        ownerQualifiedName = ownerQualified,
                        isStatic = fn.isStatic,
                        parameterTypes = paramTypes,
                        parameterNames = fn.parameterNames,
                        returnType = fn.returnType.displayName,
                        emitTypes = fn.emitTypes.map { it.displayName },
                        emitResultType = fn.emitResultType?.displayName,
                        isNekoMethod = true,
                        nekoFile = fn.span.file,
                        nekoSpan = fn.span,
                    )
                }
            }
        }

        // Also harvest from parsed ASTs in model.files.
        // This ensures methods are indexed even while the user is typing and the file
        // has a temporary syntax error (such as a trailing dot `receiver.`).
        for (nekoFile in model.files) {
            val pkg = nekoFile.packageDeclaration?.name.orEmpty()
            for (classDecl in nekoFile.classes) {
                val ownerSimple = classDecl.name
                val ownerQualified = if (pkg.isEmpty()) ownerSimple else "$pkg.$ownerSimple"
                for (fn in classDecl.functions) {
                    val paramTypes = fn.parameters.map { formatTypeReference(it.declaredType) ?: "?" }
                    val sig = "$ownerQualified#${fn.name}(${paramTypes.joinToString(",")})"
                    if (!seenSignatures.add(sig)) continue

                    result += IndexedMethod(
                        name = fn.name,
                        ownerSimpleName = ownerSimple,
                        ownerQualifiedName = ownerQualified,
                        isStatic = fn.isStatic,
                        parameterTypes = paramTypes,
                        parameterNames = fn.parameters.map { it.name },
                        returnType = formatTypeReference(fn.returnType) ?: "void",
                        emitTypes = fn.emitTypes.mapNotNull { formatTypeReference(it) },
                        emitResultType = formatTypeReference(fn.emitResultType),
                        isNekoMethod = true,
                        nekoFile = nekoFile.span.file,
                        nekoSpan = fn.span,
                    )
                }
            }
        }
        return result
    }

    private fun formatTypeReference(ref: TypeReference?): String? = when (ref) {
        null -> null
        is NamedTypeReference -> ref.name
        is ArrayTypeReference -> formatTypeReference(ref.element)?.let { "$it[]" }
        is NullableTypeReference -> formatTypeReference(ref.inner)?.let { "$it?" }
        is FunctionTypeReference -> "[${ref.parameters.mapNotNull { formatTypeReference(it) }.joinToString(", ")}] -> ${formatTypeReference(ref.returnType).orEmpty()}"
    }

    /**
     * All method names known to the index (Neko methods from [project], and Java standard library methods).
     */
    fun allMethodNames(project: Project?, includeJava: Boolean = true): Set<String> {
        val names = LinkedHashSet<String>()
        if (project != null) {
            for (m in nekoMethods(project)) {
                names += m.name
            }
        }
        if (includeJava) {
            names.addAll(javaMethodsByName.keys)
        }
        return names
    }

    /**
     * Finds methods named [name]. Neko project methods appear before Java platform methods.
     */
    fun findMethodsByName(
        name: String,
        project: Project?,
        includeJava: Boolean = true,
    ): List<IndexedMethod> {
        val result = mutableListOf<IndexedMethod>()
        if (project != null) {
            result.addAll(nekoMethods(project).filter { it.name == name })
        }
        if (includeJava) {
            javaMethodsByName[name]?.let { result.addAll(it) }
        }
        return result
    }

    /**
     * Finds methods declared on [className] (simple or qualified).
     */
    fun findMethodsByClass(
        className: String,
        project: Project?,
        includeJava: Boolean = true,
    ): List<IndexedMethod> {
        val result = mutableListOf<IndexedMethod>()
        if (project != null) {
            result.addAll(nekoMethods(project).filter {
                it.ownerSimpleName == className || it.ownerQualifiedName == className
            })
        }
        if (includeJava) {
            val javaMethods = javaMethodsByQualifiedClass[className]
                ?: javaMethodsBySimpleClass[className]
            if (javaMethods != null) {
                result.addAll(javaMethods)
            } else if (className.contains('.')) {
                runCatching {
                    val loader = ClassLoader.getPlatformClassLoader()
                    val clazz = Class.forName(className, false, loader)
                    if (Modifier.isPublic(clazz.modifiers)) {
                        for (m in clazz.methods) {
                            if (Modifier.isPublic(m.modifiers)) {
                                result.add(fromJavaMethod(clazz, m))
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * Finds static methods of [className].
     */
    fun findStaticMethodsByClass(
        className: String,
        project: Project?,
        includeJava: Boolean = true,
    ): List<IndexedMethod> = findMethodsByClass(className, project, includeJava).filter { it.isStatic }

    /**
     * Finds instance methods of [className].
     */
    fun findInstanceMethodsByClass(
        className: String,
        project: Project?,
        includeJava: Boolean = true,
    ): List<IndexedMethod> = findMethodsByClass(className, project, includeJava).filter { !it.isStatic }

    /**
     * Finds methods whose name starts with [prefix] (case-insensitive).
     */
    fun findMethodsByPrefix(
        prefix: String,
        project: Project?,
        includeJava: Boolean = true,
    ): List<IndexedMethod> {
        val result = mutableListOf<IndexedMethod>()
        if (project != null) {
            result.addAll(nekoMethods(project).filter { it.name.startsWith(prefix, ignoreCase = true) })
        }
        if (includeJava) {
            for ((name, methods) in javaMethodsByName) {
                if (name.startsWith(prefix, ignoreCase = true)) {
                    result.addAll(methods)
                }
            }
        }
        return result
    }

    private fun fromJavaMethod(clazz: Class<*>, method: Method): IndexedMethod {
        val paramTypes = method.parameterTypes.map { formatJavaType(it) }
        val paramNames = method.parameters.map { it.name }.filterNot { it.matches(Regex("arg\\d+")) }
        return IndexedMethod(
            name = method.name,
            ownerSimpleName = clazz.simpleName,
            ownerQualifiedName = clazz.name,
            isStatic = Modifier.isStatic(method.modifiers),
            parameterTypes = paramTypes,
            parameterNames = paramNames,
            returnType = formatJavaType(method.returnType),
            isNekoMethod = false,
            javaMethod = method,
        )
    }

    private fun readJavaPlatformMethods(): Map<String, List<IndexedMethod>> {
        val result = TreeMap<String, MutableList<IndexedMethod>>()
        try {
            val module = ModuleFinder.ofSystem().find(BASE_MODULE).orElse(null) ?: return emptyMap()
            val classNames = mutableListOf<String>()
            module.open().use { reader ->
                reader.list()
                    .filter { it.endsWith(".class") && !it.contains('$') }
                    .forEach { resource ->
                        val path = resource.removeSuffix(".class")
                        classNames += path.replace('/', '.')
                    }
            }
            val loader = ClassLoader.getPlatformClassLoader()
            for (className in classNames) {
                try {
                    val clazz = Class.forName(className, false, loader)
                    if (!Modifier.isPublic(clazz.modifiers)) continue
                    for (method in clazz.declaredMethods) {
                        if (!Modifier.isPublic(method.modifiers)) continue
                        val indexed = fromJavaMethod(clazz, method)
                        result.getOrPut(method.name) { mutableListOf() } += indexed
                    }
                } catch (_: Throwable) {
                    // Skip classes that fail to load
                }
            }
        } catch (_: Throwable) {
            return emptyMap()
        }
        return result
    }

    private fun formatJavaType(type: Class<*>): String = when {
        type.isArray -> "${formatJavaType(type.componentType)}[]"
        type == Void.TYPE -> "void"
        type.name == "java.lang.String" -> "string"
        type.name == "java.lang.Object" -> "Object"
        type.isPrimitive -> type.name
        else -> type.simpleName
    }
}
