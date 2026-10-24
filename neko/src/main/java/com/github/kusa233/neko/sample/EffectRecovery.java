package com.github.kusa233.neko.sample;

import com.github.kusa233.neko.stdlib.emit.EmitYieldException;

class EffectRecovery {
    public static void main(String[] args) {
        nyanTest();
    }

    public static void nyanTest() {
        // v: int = try a() { ... }
        int v;
        try {
            v = a();
        } catch (EmitYieldException e) {
            v = (int) e.data;
        }
    }

    public static int a() throws EmitYieldException {
        // return b()
        return b();
    }

    public static int b() throws EmitYieldException {
        // v: int = scratch 1, 2
        int v = nyanTest$scratch$0(1, 2);
        // return v * 10
        return v * 10;
    }

    /**
     * { var1, var2 ->
     *     if (a + b == 3) {
     *         nyan a + b // Should be 1 + 2
     *     } else {
     *         yield 10 // v should be 10 if a + b not 3
     *     }
     * }
     */
    private static int nyanTest$scratch$0(int var1, int var2) throws EmitYieldException {
        if (var1 + var2 == 3) {
            return var1 + var2;
        } else {
            throw new EmitYieldException(10);
        }
    }
}
