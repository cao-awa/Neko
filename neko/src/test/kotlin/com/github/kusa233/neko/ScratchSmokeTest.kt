package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A first end-to-end check of `scratch`, `nyan` and the new `yield`.
 *
 * Deliberately tiny and deliberately first: this is the shape the sample in
 * `samples/.../EffectRecovery.neko` describes, and getting it wrong is cheap here.
 */
class ScratchSmokeTest {

    @Test
    fun `a scratch calls the handler its emit clause matches`() {
        val program = TestSupport.compile(
            TestSupport.file(
                "class Main() {",
                "    static {",
                "        fun main() {",
                "            println(nyanTest())",
                "        }",
                "",
                "        fun nyanTest(): int {",
                "            v: int = try a() { a, b ->",
                "                if (a + b == 3) {",
                "                    nyan a + b",
                "                } else {",
                "                    yield 10",
                "                }",
                "            }",
                "            return v",
                "        }",
                "",
                "        fun a(): int {",
                "            return b()",
                "        } emit [int, int] -> int",
                "",
                "        fun b(): int {",
                "            v: int = scratch 1, 2",
                "            return v * 10",
                "        } emit [int, int] -> int",
                "    }",
                "}",
            ),
        )

        assertEquals(emptyList(), TestSupport.errorLocations(program))
    }

    @Test
    fun `a scratch reaches the handler and its nyan answer comes back`() {
        val program = TestSupport.load(
            TestSupport.file(
                "class Main() {",
                "    static {",
                "        fun main() {",
                "            println(nyanTest())",
                "        }",
                "",
                "        fun nyanTest(): int {",
                "            v: int = try a() { a, b ->",
                "                if (a + b == 3) {",
                "                    nyan a + b",
                "                } else {",
                "                    yield 10",
                "                }",
                "            }",
                "            return v",
                "        }",
                "",
                "        fun a(): int {",
                "            return b()",
                "        } emit [int, int] -> int",
                "",
                "        fun b(): int {",
                "            v: int = scratch 1, 2",
                "            return v * 10",
                "        } emit [int, int] -> int",
                "    }",
                "}",
            ),
        )

        assertEquals(
            "30\n",
            program.capturingOutput {
                program.invoke("com.github.kusa233.test.Main", "main")
            },
        )
    }
}
