package org.basic2asm.ast;

/**
 * Storage type of a BASIC variable. The PIC is an 8-bit machine, so BYTE is the
 * natural width; WORD (16-bit) is supported for storage, assignment, addition,
 * subtraction and comparison. BIT maps onto a single allocated byte for
 * simplicity but is constrained to 0/1 by the generator.
 */
public enum VarType {
    BYTE(1),
    WORD(2),
    BIT(1);

    public final int bytes;

    VarType(int bytes) { this.bytes = bytes; }
}
