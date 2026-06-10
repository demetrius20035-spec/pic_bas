package org.basic2asm.diag;

/**
 * Thrown to abort compilation when a phase cannot meaningfully continue.
 * Carries an optional source location so the driver can report it nicely.
 */
public class CompileException extends RuntimeException {

    private final int line;
    private final int column;

    public CompileException(String message) {
        this(message, 0, 0);
    }

    public CompileException(String message, int line, int column) {
        super(message);
        this.line = line;
        this.column = column;
    }

    public int getLine()   { return line; }
    public int getColumn() { return column; }
}
