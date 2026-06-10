package org.basic2asm.sema;

import org.basic2asm.ast.Expr;
import org.basic2asm.ast.Program;
import org.basic2asm.ast.Stmt;
import org.basic2asm.diag.DiagnosticReporter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static checks performed after parsing and before code generation. Keeping
 * analysis separate from synthesis gives a clear three-stage pipeline (parse →
 * analyze → generate) and lets us reject obviously-wrong programs with good
 * diagnostics before the generator runs.
 *
 * <p>Checks performed: duplicate subroutine names, GOTO/GOSUB targets resolve to
 * a label in the same scope, CALL / function-call targets exist with the right
 * arity, and assignments do not target constants.</p>
 */
public final class SemanticAnalyzer {

    private final Program program;
    private final DiagnosticReporter reporter;

    private final Map<String, Integer> subArity = new HashMap<>(); // name -> param count
    private final Set<String> functions = new HashSet<>();
    private final Set<String> constants = new HashSet<>();

    public SemanticAnalyzer(Program program, DiagnosticReporter reporter) {
        this.program = program;
        this.reporter = reporter;
    }

    public void analyze() {
        // Collect subroutine declarations (rejecting duplicates).
        for (Stmt.SubDecl s : program.subs) {
            String key = s.name.toUpperCase();
            if (s.name.equalsIgnoreCase("ISR")) {
                if (!s.params.isEmpty()) {
                    reporter.error(s.line, s.column, "the ISR handler cannot take parameters");
                }
                continue;
            }
            if (subArity.containsKey(key)) {
                reporter.error(s.line, s.column, "duplicate subroutine/function '" + s.name + "'");
            }
            subArity.put(key, s.params.size());
            if (s.isFunction) functions.add(key);
        }
        // Collect top-level constants.
        for (Stmt st : program.main) {
            if (st instanceof Stmt.Const) {
                constants.add(((Stmt.Const) st).name.toUpperCase());
            }
        }
        // Validate the main body and each subroutine body in its own label scope.
        checkBody(program.main);
        for (Stmt.SubDecl s : program.subs) {
            checkBody(s.body);
        }
    }

    private void checkBody(List<Stmt> body) {
        Set<String> labels = new HashSet<>();
        collectLabels(body, labels);
        walk(body, labels);
    }

    private void collectLabels(List<Stmt> body, Set<String> labels) {
        for (Stmt s : body) {
            if (s instanceof Stmt.Label) {
                String n = ((Stmt.Label) s).name.toUpperCase();
                if (!labels.add(n)) {
                    reporter.error(s.line, s.column, "duplicate label '" + ((Stmt.Label) s).name + "'");
                }
            }
            for (List<Stmt> child : children(s)) {
                collectLabels(child, labels);
            }
        }
    }

    private void walk(List<Stmt> body, Set<String> labels) {
        for (Stmt s : body) {
            if (s instanceof Stmt.Goto) {
                Stmt.Goto g = (Stmt.Goto) s;
                if (!labels.contains(g.label.toUpperCase())) {
                    reporter.error(g.line, g.column, "GOTO to undefined label '" + g.label + "'");
                }
            } else if (s instanceof Stmt.Gosub) {
                Stmt.Gosub g = (Stmt.Gosub) s;
                if (!labels.contains(g.label.toUpperCase())) {
                    reporter.error(g.line, g.column, "GOSUB to undefined label '" + g.label + "'");
                }
            } else if (s instanceof Stmt.CallStmt) {
                Stmt.CallStmt c = (Stmt.CallStmt) s;
                checkCall(c.name, c.args.size(), c.line, c.column, false);
                for (Expr a : c.args) checkExpr(a);
            } else if (s instanceof Stmt.Assign) {
                Stmt.Assign a = (Stmt.Assign) s;
                if (constants.contains(a.target.name.toUpperCase())) {
                    reporter.error(a.line, a.column, "cannot assign to constant '" + a.target.name + "'");
                }
                checkExpr(a.value);
            } else if (s instanceof Stmt.Dim) {
                if (((Stmt.Dim) s).init != null) checkExpr(((Stmt.Dim) s).init);
            } else if (s instanceof Stmt.If) {
                checkExpr(((Stmt.If) s).cond);
                for (Stmt.ElseIf ei : ((Stmt.If) s).elseIfs) checkExpr(ei.cond);
            } else if (s instanceof Stmt.While) {
                checkExpr(((Stmt.While) s).cond);
            } else if (s instanceof Stmt.DoLoop) {
                if (((Stmt.DoLoop) s).cond != null) checkExpr(((Stmt.DoLoop) s).cond);
            } else if (s instanceof Stmt.UartWrite) {
                checkExpr(((Stmt.UartWrite) s).value);
            } else if (s instanceof Stmt.Delay) {
                checkExpr(((Stmt.Delay) s).amount);
            } else if (s instanceof Stmt.Poke) {
                checkExpr(((Stmt.Poke) s).value);
            } else if (s instanceof Stmt.Print) {
                for (Stmt.Print.Item it : ((Stmt.Print) s).items) {
                    if (it.expr != null) checkExpr(it.expr);
                }
            }
            for (List<Stmt> child : children(s)) {
                walk(child, labels);
            }
        }
    }

    private void checkExpr(Expr e) {
        if (e instanceof Expr.Call) {
            Expr.Call c = (Expr.Call) e;
            checkCall(c.name, c.args.size(), c.line, c.column, true);
            for (Expr a : c.args) checkExpr(a);
        } else if (e instanceof Expr.Unary) {
            checkExpr(((Expr.Unary) e).operand);
        } else if (e instanceof Expr.Binary) {
            checkExpr(((Expr.Binary) e).left);
            checkExpr(((Expr.Binary) e).right);
        } else if (e instanceof Expr.Peek) {
            checkExpr(((Expr.Peek) e).address);
        } else if (e instanceof Expr.AdcRead) {
            checkExpr(((Expr.AdcRead) e).channel);
        }
    }

    private void checkCall(String name, int argc, int line, int col, boolean asFunction) {
        Integer arity = subArity.get(name.toUpperCase());
        if (arity == null) {
            reporter.error(line, col, (asFunction ? "function" : "subroutine")
                    + " '" + name + "' is not defined");
            return;
        }
        if (arity != argc) {
            reporter.error(line, col, "'" + name + "' expects " + arity
                    + " argument(s), got " + argc);
        }
    }

    /** Child statement lists of a compound statement (for recursive walking). */
    private List<List<Stmt>> children(Stmt s) {
        java.util.List<List<Stmt>> out = new java.util.ArrayList<>();
        if (s instanceof Stmt.If) {
            Stmt.If i = (Stmt.If) s;
            out.add(i.thenBody);
            for (Stmt.ElseIf ei : i.elseIfs) out.add(ei.body);
            if (i.elseBody != null) out.add(i.elseBody);
        } else if (s instanceof Stmt.For) {
            out.add(((Stmt.For) s).body);
        } else if (s instanceof Stmt.While) {
            out.add(((Stmt.While) s).body);
        } else if (s instanceof Stmt.DoLoop) {
            out.add(((Stmt.DoLoop) s).body);
        }
        return out;
    }
}
