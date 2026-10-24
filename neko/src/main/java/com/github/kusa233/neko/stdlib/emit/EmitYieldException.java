package com.github.kusa233.neko.stdlib.emit;

/**
 * The carrier a {@code yield} throws.
 *
 * It is checked rather than unchecked on purpose: a {@code yield} is only legal inside a handler that
 * a {@code try} declared, and every method between them therefore has to say it may pass one on. That
 * is what makes the effect visible in the class file rather than a surprise at run time.
 *
 * One field is enough: the {@code try} that declared the handler is the only thing that ever catches
 * this, and it reads {@link #data} to fill the value the {@code try} expression produces. The handler's
 * other exits answer by returning, so nothing else has to travel back.
 */
public class EmitYieldException extends Exception {
    public final Object data;

    public EmitYieldException(Object var1) {
        this.data = var1;
    }
}
