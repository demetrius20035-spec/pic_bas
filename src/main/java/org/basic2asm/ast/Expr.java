package org.basic2asm.ast;

import java.util.List;

/**
 * Expression AST nodes. Concrete expressions are modelled as nested classes so
 * that the whole grammar fits in one readable unit while remaining easy to
 * pattern-match on in the analyzer and code generator.
 */
public abstract class Expr {

    public final int line;
    public final int column;

    protected Expr(int line, int column) {
        this.line = line;
        this.column = column;
    }

    /** Integer literal (any radix already folded by the lexer). */
    public static final class Num extends Expr {
        public final int value;
        public Num(int value, int line, int column) { super(line, column); this.value = value; }
    }

    /**
     * A bare identifier reference. Classification into a constant, a user
     * variable, a single pin (e.g. RB0) or a port data register (e.g. PORTB) is
     * deferred to the code generator, which has the target {@link
     * org.basic2asm.chip.ChipDefinition} and symbol table needed to resolve it.
     */
    public static final class Name extends Expr {
        public final String name;
        public Name(String name, int line, int column) { super(line, column); this.name = name; }
    }

    /** PEEK(addr) — read an arbitrary file register / SFR by address or symbol. */
    public static final class Peek extends Expr {
        public final Expr address;   // PEEK(addr)
        public Peek(Expr address, int line, int column) { super(line, column); this.address = address; }
    }

    /** ADC(channel) — read the analog-to-digital converter (8-bit result). */
    public static final class AdcRead extends Expr {
        public final Expr channel;
        public AdcRead(Expr channel, int line, int column) { super(line, column); this.channel = channel; }
    }

    /** Unary operation: NEG (-x) or NOT (bitwise complement / logical not). */
    public static final class Unary extends Expr {
        public enum Op { NEG, NOT }
        public final Op op;
        public final Expr operand;
        public Unary(Op op, Expr operand, int line, int column) {
            super(line, column); this.op = op; this.operand = operand;
        }
    }

    /** Binary operation. Comparisons evaluate to 1 (true) or 0 (false). */
    public static final class Binary extends Expr {
        public enum Op {
            ADD, SUB, MUL, DIV, MOD,
            AND, OR, XOR, SHL, SHR,
            EQ, NE, LT, LE, GT, GE
        }
        public final Op op;
        public final Expr left;
        public final Expr right;
        public Binary(Op op, Expr left, Expr right, int line, int column) {
            super(line, column); this.op = op; this.left = left; this.right = right;
        }
    }

    /** Call to a user FUNCTION used in expression position. */
    public static final class Call extends Expr {
        public final String name;
        public final List<Expr> args;
        public Call(String name, List<Expr> args, int line, int column) {
            super(line, column); this.name = name; this.args = args;
        }
    }
}
