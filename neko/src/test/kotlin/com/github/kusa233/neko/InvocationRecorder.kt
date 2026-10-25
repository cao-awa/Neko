package com.github.kusa233.neko

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Reads back the calls a generated method makes.
 *
 * A test about **overload selection** has to look at the descriptor, not at the diagnostic: both
 * `sleep(long)` and `sleep(Duration)` are "a call to sleep", so only the descriptor distinguishes
 * them. The analyzer records its choice on the AST, but the AST is not what runs — the descriptor in
 * the class file is. So this reads the byte that was actually written.
 *
 * `invokedynamic` is deliberately not covered: the generator emits it for nothing today, and a
 * visitor that silently missed one would be a worse trap than an obvious omission.
 */
object InvocationRecorder {

    /**
     * Every `invokestatic` / `invokevirtual` / `invokeinterface` / `invokespecial` in [methodName] of
     * [classBytes], as `Owner.name:(descriptor)V`.
     *
     * [parameterCount] selects the overload when a class declares two methods of the same name — which
     * a Neko `main()` does: the source-level `main()` plus the synthetic `main(String[])` that makes
     * the class runnable. The zero-argument one is the program's own; the other just forwards to it.
     */
    fun descriptorsOf(classBytes: ByteArray, methodName: String, parameterCount: Int): List<String> {
        val found = mutableListOf<String>()
        ClassReader(classBytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (name != methodName) return null
                    // `descriptor` looks like `(J)V`; count the parameters by scanning the prefix.
                    if (parameterCountOf(descriptor) != parameterCount) return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            found += "$owner.$name:$descriptor"
                        }
                    }
                }
            },
            0,
        )
        return found
    }

    /** The number of parameters in a method [descriptor] such as `([IJLjava/lang/String;)V`. */
    private fun parameterCountOf(descriptor: String): Int {
        var count = 0
        var index = 1 // skip '('
        while (index < descriptor.length && descriptor[index] != ')') {
            var cursor = index
            while (descriptor[cursor] == '[') cursor++
            // A reference type is one parameter however long its name; a primitive is one character.
            count++
            index = if (descriptor[cursor] == 'L') {
                descriptor.indexOf(';', cursor) + 1
            } else {
                cursor + 1
            }
        }
        return count
    }
}
