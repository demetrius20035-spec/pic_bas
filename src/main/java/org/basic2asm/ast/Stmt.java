package org.basic2asm.ast;

import java.util.List;

/**
 * Statement AST nodes for the BASIC dialect. Like {@link Expr}, concrete
 * statements are nested classes; the code generator dispatches on their type.
 */
public abstract class Stmt {

    public final int line;
    public final int column;

    protected Stmt(int line, int column) {
        this.line = line;
        this.column = column;
    }

    // ------------------------------------------------------------------
    // Assignment targets (l-values)
    // ------------------------------------------------------------------

    /**
     * Assignment target named by a bare identifier. As with {@link Expr.Name},
     * the generator resolves it to a variable, a port register or a single pin.
     */
    public static final class Target {
        public final String name;
        public final int line, column;
        public Target(String name, int line, int column) {
            this.name = name; this.line = line; this.column = column;
        }
    }

    // ------------------------------------------------------------------
    // Statements
    // ------------------------------------------------------------------

    public static final class Dim extends Stmt {
        public final String name;
        public final VarType type;
        public final Expr init; // may be null
        public Dim(String name, VarType type, Expr init, int line, int column) {
            super(line, column); this.name = name; this.type = type; this.init = init;
        }
    }

    public static final class Const extends Stmt {
        public final String name;
        public final Expr value;
        public Const(String name, Expr value, int line, int column) {
            super(line, column); this.name = name; this.value = value;
        }
    }

    public static final class Assign extends Stmt {
        public final Target target;
        public final Expr value;
        public Assign(Target target, Expr value, int line, int column) {
            super(line, column); this.target = target; this.value = value;
        }
    }

    public static final class ElseIf {
        public final Expr cond;
        public final List<Stmt> body;
        public ElseIf(Expr cond, List<Stmt> body) { this.cond = cond; this.body = body; }
    }

    public static final class If extends Stmt {
        public final Expr cond;
        public final List<Stmt> thenBody;
        public final List<ElseIf> elseIfs;
        public final List<Stmt> elseBody; // may be null
        public If(Expr cond, List<Stmt> thenBody, List<ElseIf> elseIfs, List<Stmt> elseBody,
                  int line, int column) {
            super(line, column);
            this.cond = cond; this.thenBody = thenBody; this.elseIfs = elseIfs; this.elseBody = elseBody;
        }
    }

    public static final class For extends Stmt {
        public final String var;
        public final Expr from;
        public final Expr to;
        public final Expr step; // may be null (defaults to 1)
        public final List<Stmt> body;
        public For(String var, Expr from, Expr to, Expr step, List<Stmt> body, int line, int column) {
            super(line, column);
            this.var = var; this.from = from; this.to = to; this.step = step; this.body = body;
        }
    }

    public static final class While extends Stmt {
        public final Expr cond;
        public final List<Stmt> body;
        public While(Expr cond, List<Stmt> body, int line, int column) {
            super(line, column); this.cond = cond; this.body = body;
        }
    }

    public static final class DoLoop extends Stmt {
        public enum Kind { INFINITE, PRE_WHILE, PRE_UNTIL, POST_WHILE, POST_UNTIL }
        public final Kind kind;
        public final Expr cond; // may be null for INFINITE
        public final List<Stmt> body;
        public DoLoop(Kind kind, Expr cond, List<Stmt> body, int line, int column) {
            super(line, column); this.kind = kind; this.cond = cond; this.body = body;
        }
    }

    public static final class Goto extends Stmt {
        public final String label;
        public Goto(String label, int line, int column) { super(line, column); this.label = label; }
    }

    public static final class Label extends Stmt {
        public final String name;
        public Label(String name, int line, int column) { super(line, column); this.name = name; }
    }

    public static final class Gosub extends Stmt {
        public final String label;
        public Gosub(String label, int line, int column) { super(line, column); this.label = label; }
    }

    public static final class Return extends Stmt {
        public final Expr value; // for FUNCTION return; may be null
        public Return(Expr value, int line, int column) { super(line, column); this.value = value; }
    }

    public static final class SubDecl extends Stmt {
        public final boolean isFunction;
        public final String name;
        public final List<String> params;
        public final List<Stmt> body;
        public SubDecl(boolean isFunction, String name, List<String> params, List<Stmt> body,
                       int line, int column) {
            super(line, column);
            this.isFunction = isFunction; this.name = name; this.params = params; this.body = body;
        }
    }

    public static final class CallStmt extends Stmt {
        public final String name;
        public final List<Expr> args;
        public CallStmt(String name, List<Expr> args, int line, int column) {
            super(line, column); this.name = name; this.args = args;
        }
    }

    public static final class PinOp extends Stmt {
        public enum Kind { HIGH, LOW, TOGGLE }
        public final Kind kind;
        public final String pin; // raw pin name, e.g. "RB0"
        public PinOp(Kind kind, String pin, int line, int column) {
            super(line, column); this.kind = kind; this.pin = pin;
        }
    }

    /** OUTPUT / INPUT direction control for a pin or a whole port. */
    public static final class Direction extends Stmt {
        public final boolean output;
        public final String target; // pin name (RB0) or port register (PORTB)
        public Direction(boolean output, String target, int line, int column) {
            super(line, column); this.output = output; this.target = target;
        }
    }

    public static final class Delay extends Stmt {
        public final boolean microseconds;
        public final Expr amount;
        public Delay(boolean microseconds, Expr amount, int line, int column) {
            super(line, column); this.microseconds = microseconds; this.amount = amount;
        }
    }

    public static final class UartInit extends Stmt {
        public UartInit(int line, int column) { super(line, column); }
    }

    public static final class UartWrite extends Stmt {
        public final Expr value;
        public UartWrite(Expr value, int line, int column) { super(line, column); this.value = value; }
    }

    public static final class Print extends Stmt {
        /** A print item is either a string literal or an expression (printed as decimal). */
        public static final class Item {
            public final String text;   // non-null => string literal
            public final Expr expr;     // non-null => numeric expression
            public Item(String text, Expr expr) { this.text = text; this.expr = expr; }
        }
        public final List<Item> items;
        public final boolean newline; // append CR/LF unless trailing ';'
        public Print(List<Item> items, boolean newline, int line, int column) {
            super(line, column); this.items = items; this.newline = newline;
        }
    }

    public static final class Poke extends Stmt {
        public final String register;
        public final Expr value;
        public Poke(String register, Expr value, int line, int column) {
            super(line, column); this.register = register; this.value = value;
        }
    }

    public static final class End extends Stmt {
        public End(int line, int column) { super(line, column); }
    }
}
