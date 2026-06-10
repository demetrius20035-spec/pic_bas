package org.basic2asm.codegen;

import org.basic2asm.ast.Expr;
import org.basic2asm.ast.Program;
import org.basic2asm.ast.Stmt;
import org.basic2asm.ast.VarType;
import org.basic2asm.chip.ChipDefinition;
import org.basic2asm.chip.CoreType;
import org.basic2asm.chip.Peripheral;
import org.basic2asm.chip.PortInfo;
import org.basic2asm.config.Config;
import org.basic2asm.diag.CompileException;
import org.basic2asm.diag.DiagnosticReporter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates a parsed {@link Program} into MPASM-compatible assembly for a
 * given {@link ChipDefinition}. Emits a header, fuse configuration, RAM block,
 * reset/interrupt vectors, the main program, user subroutines and any required
 * runtime helper routines. Hardware nuances (banking, baseline TRIS/OPTION
 * instructions, comparator/analog disabling) are handled per core/chip.
 */
public final class CodeGenerator {

    private final Program program;
    private final ChipDefinition chip;
    private final Config config;
    private final DiagnosticReporter reporter;
    private final CoreType core;

    private final Symbols symbols = new Symbols();

    // Output buffers (assembled in order at the end).
    private final AsmBuilder bMain;
    private final AsmBuilder bSubs;
    private final AsmBuilder bRuntime;
    private final AsmBuilder bIsr;

    // RAM allocation: ordered name -> size in bytes.
    private final Map<String, Integer> ram = new LinkedHashMap<>();

    // Feature flags discovered during generation.
    private boolean usesAdc;
    private boolean usesUart;
    private boolean hasIsr;
    private final java.util.Set<String> emittedRoutines = new java.util.HashSet<>();

    // Temporary register pool.
    private int tempTop = 0;
    private int tempMax = 0;

    private int labelSeq = 0;

    private final String bank0Anchor;     // a bank-0 SFR used to restore the bank
    private final long tcyHz;             // instruction cycles per second (Fosc/4)

    // Scope for the parameters of the subroutine currently being generated.
    private Map<String, Symbols.Var> localScope = null;

    public CodeGenerator(Program program, ChipDefinition chip, Config config, DiagnosticReporter reporter) {
        this.program = program;
        this.chip = chip;
        this.config = config;
        this.reporter = reporter;
        this.core = chip.getCore();
        boolean c = config.comments;
        this.bMain = new AsmBuilder(c);
        this.bSubs = new AsmBuilder(c);
        this.bRuntime = new AsmBuilder(c);
        this.bIsr = new AsmBuilder(c);
        this.bank0Anchor = chip.getPorts().isEmpty() ? "STATUS" : chip.getPorts().get(0).register;
        this.tcyHz = config.clockHz / 4;
    }

    /** Result of a successful generation. */
    public static final class Result {
        public final String asm;
        public final List<String> mapLines;
        public final int estimatedWords;
        public Result(String asm, List<String> mapLines, int estimatedWords) {
            this.asm = asm; this.mapLines = mapLines; this.estimatedWords = estimatedWords;
        }
    }

    // ------------------------------------------------------------------
    // Top-level driver
    // ------------------------------------------------------------------

    public Result generate() {
        try {
            collectDeclarations();
            generateMainProgram();
            generateSubroutines();
            generateIsr();
            flushRuntime();
        } catch (CompileException e) {
            reporter.error(e.getLine(), e.getColumn(), e.getMessage());
        }
        if (reporter.hasErrors()) {
            return null;
        }
        return assemble();
    }

    /** First pass: register constants, subroutines and pre-scan labels. */
    private void collectDeclarations() {
        // Subroutines / functions and the special ISR handler.
        for (Stmt.SubDecl s : program.subs) {
            if (s.name.equalsIgnoreCase("ISR")) {
                hasIsr = true;
                if (core.isBaseline()) {
                    reporter.error(s.line, s.column,
                            "interrupts (ISR) are not available on the baseline core of " + chip.getName());
                }
                continue;
            }
            String label = "sub_" + sanitize(s.name);
            symbols.addSub(new Symbols.Sub(s.name, s.isFunction, s.params, label, s));
            // Allocate parameter storage.
            for (String p : s.params) {
                String reg = "p_" + sanitize(s.name) + "_" + sanitize(p);
                reserveRam(reg, 1);
            }
        }
        // Constants from the main body (folded immediately).
        for (Stmt st : program.main) {
            if (st instanceof Stmt.Const) {
                Stmt.Const c = (Stmt.Const) st;
                symbols.addConst(c.name, evalConst(c.value));
            }
        }
        // Pre-scan labels in the main body so forward GOTO works.
        scanLabels(program.main);
    }

    private void scanLabels(List<Stmt> body) {
        for (Stmt st : body) {
            if (st instanceof Stmt.Label) {
                Stmt.Label l = (Stmt.Label) st;
                symbols.addLabel(l.name, "lbl_" + sanitize(l.name));
            }
        }
    }

    private void generateMainProgram() {
        bMain.label("main");
        emitDigitalIoPrologue();
        if (usesAdcInProgram()) {
            usesAdc = true;
            emitAdcInitPrologue();
        }
        if (core.isBaseline() && baselineUsesTris()) {
            emitBaselineTrisShadowInit();
        }
        genBlock(program.main);
        // Fall off the end -> trap in an endless loop so the device is well-defined.
        bMain.blank();
        bMain.comment("end of program: trap here");
        bMain.label("__halt");
        bMain.insn("goto", "__halt");
    }

    private void generateSubroutines() {
        for (Stmt.SubDecl s : program.subs) {
            if (s.name.equalsIgnoreCase("ISR")) {
                continue; // emitted at the vector
            }
            Symbols.Sub sub = symbols.findSub(s.name);
            bSubs.blank();
            bSubs.comment((s.isFunction ? "FUNCTION " : "SUB ") + s.name);
            bSubs.label(sub.label);
            // Build the parameter scope.
            Map<String, Symbols.Var> scope = new LinkedHashMap<>();
            for (String p : s.params) {
                String reg = "p_" + sanitize(s.name) + "_" + sanitize(p);
                scope.put(p.toUpperCase(), new Symbols.Var(p, VarType.BYTE, reg));
            }
            Map<String, Symbols.Var> prev = localScope;
            localScope = scope;
            AsmBuilder prevTarget = currentTarget;
            currentTarget = bSubs;
            scanLabels(s.body);
            genBlock(s.body);
            currentTarget = prevTarget;
            localScope = prev;
            bSubs.insn(core.isBaseline() ? "retlw" : "return", core.isBaseline() ? "0x00" : "");
        }
    }

    // The builder the current statement stream writes to (main vs. sub body).
    private AsmBuilder currentTarget;

    private AsmBuilder out() {
        return currentTarget != null ? currentTarget : bMain;
    }

    // ------------------------------------------------------------------
    // Statement generation
    // ------------------------------------------------------------------

    private void genBlock(List<Stmt> body) {
        AsmBuilder save = currentTarget;
        if (currentTarget == null) currentTarget = bMain;
        for (Stmt st : body) {
            genStmt(st);
        }
        currentTarget = save;
    }

    private void genStmt(Stmt st) {
        if (st instanceof Stmt.Const) {
            return; // already folded
        } else if (st instanceof Stmt.Dim) {
            genDim((Stmt.Dim) st);
        } else if (st instanceof Stmt.Assign) {
            genAssign((Stmt.Assign) st);
        } else if (st instanceof Stmt.If) {
            genIf((Stmt.If) st);
        } else if (st instanceof Stmt.For) {
            genFor((Stmt.For) st);
        } else if (st instanceof Stmt.While) {
            genWhile((Stmt.While) st);
        } else if (st instanceof Stmt.DoLoop) {
            genDoLoop((Stmt.DoLoop) st);
        } else if (st instanceof Stmt.Label) {
            out().label(symbols.findLabel(((Stmt.Label) st).name));
        } else if (st instanceof Stmt.Goto) {
            genGoto((Stmt.Goto) st);
        } else if (st instanceof Stmt.Gosub) {
            genGosub((Stmt.Gosub) st);
        } else if (st instanceof Stmt.Return) {
            genReturn((Stmt.Return) st);
        } else if (st instanceof Stmt.CallStmt) {
            genCall((Stmt.CallStmt) st);
        } else if (st instanceof Stmt.PinOp) {
            genPinOp((Stmt.PinOp) st);
        } else if (st instanceof Stmt.Direction) {
            genDirection((Stmt.Direction) st);
        } else if (st instanceof Stmt.Delay) {
            genDelay((Stmt.Delay) st);
        } else if (st instanceof Stmt.UartInit) {
            genUartInit((Stmt.UartInit) st);
        } else if (st instanceof Stmt.UartWrite) {
            genUartWrite((Stmt.UartWrite) st);
        } else if (st instanceof Stmt.Print) {
            genPrint((Stmt.Print) st);
        } else if (st instanceof Stmt.Poke) {
            genPoke((Stmt.Poke) st);
        } else if (st instanceof Stmt.End) {
            out().comment("END");
            String halt = uniqueLabel("end");
            out().label(halt);
            out().insn("goto", halt);
        } else {
            reporter.error(st.line, st.column, "internal: unhandled statement " + st.getClass().getSimpleName());
        }
    }

    private void genDim(Stmt.Dim d) {
        if (symbols.findVar(d.name) != null) {
            reporter.warning(d.line, d.column, "variable '" + d.name + "' re-declared");
        }
        VarType type = d.type;
        if (type == VarType.WORD) {
            reporter.warning(d.line, d.column,
                    "WORD variables are stored as 16 bits but arithmetic is 8-bit; using the low byte");
            type = VarType.BYTE; // honest downgrade for the 8-bit engine
        }
        Symbols.Var v = defineVar(d.name, type);
        if (d.init != null) {
            genExprToW(d.init);
            comment("init " + d.name);
            storeWToVar(v);
        }
    }

    private void genAssign(Stmt.Assign a) {
        Resolved r = resolve(a.target.name, a.target.line, a.target.column);
        switch (r.kind) {
            case VAR:
                genExprToW(a.value);
                comment("store " + a.target.name);
                storeWToVar(r.var);
                break;
            case PORT:
                genExprToW(a.value);
                bank0();
                out().insn("movwf", r.register, "write " + r.register);
                break;
            case PIN:
                genAssignPin(r, a.value);
                break;
            case CONST:
                reporter.error(a.line, a.column, "cannot assign to constant '" + a.target.name + "'");
                break;
        }
    }

    private void genAssignPin(Resolved r, Expr value) {
        Integer lit = tryConst(value);
        bank0();
        if (lit != null) {
            if ((lit & 1) != 0 || lit > 1) {
                out().insn("bsf", r.register + ", " + r.bit, "set " + r.pinName);
            } else {
                out().insn("bcf", r.register + ", " + r.bit, "clear " + r.pinName);
            }
            return;
        }
        genExprToW(value);
        int t = allocTemp();
        out().insn("movwf", temp(t));
        out().insn("bcf", r.register + ", " + r.bit, "assume low");
        out().insn("movf", temp(t) + ", f", "test value");
        out().insn("btfss", "STATUS, Z");
        out().insn("bsf", r.register + ", " + r.bit, "set if non-zero");
        freeTemp();
    }

    private void genIf(Stmt.If s) {
        String endLabel = uniqueLabel("endif");
        // then
        String nextLabel = uniqueLabel("else");
        genCondFalseJump(s.cond, nextLabel);
        genBlock(s.thenBody);
        out().insn("goto", endLabel);
        out().label(nextLabel);
        // elseif chain
        for (Stmt.ElseIf ei : s.elseIfs) {
            String n2 = uniqueLabel("else");
            genCondFalseJump(ei.cond, n2);
            genBlock(ei.body);
            out().insn("goto", endLabel);
            out().label(n2);
        }
        // else
        if (s.elseBody != null) {
            genBlock(s.elseBody);
        }
        out().label(endLabel);
    }

    private void genWhile(Stmt.While s) {
        String top = uniqueLabel("while");
        String end = uniqueLabel("wend");
        out().label(top);
        genCondFalseJump(s.cond, end);
        genBlock(s.body);
        out().insn("goto", top);
        out().label(end);
    }

    private void genDoLoop(Stmt.DoLoop s) {
        String top = uniqueLabel("do");
        String end = uniqueLabel("loop");
        out().label(top);
        if (s.kind == Stmt.DoLoop.Kind.PRE_WHILE) {
            genCondFalseJump(s.cond, end);
        } else if (s.kind == Stmt.DoLoop.Kind.PRE_UNTIL) {
            genCondTrueJump(s.cond, end);
        }
        genBlock(s.body);
        switch (s.kind) {
            case POST_WHILE: genCondTrueJump(s.cond, top); break;
            case POST_UNTIL: genCondFalseJump(s.cond, top); break;
            default:         out().insn("goto", top); break;
        }
        out().label(end);
    }

    private void genFor(Stmt.For s) {
        Symbols.Var v = symbols.findVar(s.var);
        if (v == null && (localScope == null || !localScope.containsKey(s.var.toUpperCase()))) {
            v = defineVar(s.var, VarType.BYTE);
        } else if (v == null) {
            v = localScope.get(s.var.toUpperCase());
        }
        String top = uniqueLabel("for");
        String end = uniqueLabel("next");
        String toReg = "for" + (labelSeq) + "_to";
        reserveRam(toReg, 1);

        // var = from
        genExprToW(s.from);
        storeWToVar(v);
        // to -> toReg
        genExprToW(s.to);
        out().insn("movwf", toReg, "loop limit");

        Integer stepConst = s.step == null ? Integer.valueOf(1) : tryConst(s.step);
        String stepReg = null;
        if (stepConst == null) {
            stepReg = "for" + (labelSeq) + "_step";
            reserveRam(stepReg, 1);
            genExprToW(s.step);
            out().insn("movwf", stepReg, "loop step");
        }

        out().label(top);
        // termination test
        boolean negative = stepConst != null && (byte) (int) stepConst < 0;
        if (!negative) {
            out().insn("movf", varRef(v) + ", w");
            out().insn("subwf", toReg + ", w", "to - var");
            out().insn("btfss", "STATUS, C", "exit when var > to");
            out().insn("goto", end);
        } else {
            out().insn("movf", toReg + ", w");
            out().insn("subwf", varRef(v) + ", w", "var - to");
            out().insn("btfss", "STATUS, C", "exit when var < to");
            out().insn("goto", end);
        }
        genBlock(s.body);
        // increment
        if (stepConst != null) {
            int sc = stepConst & 0xFF;
            if (sc == 1) {
                out().insn("incf", varRef(v) + ", f", "var++");
            } else if (sc == 0xFF) {
                out().insn("decf", varRef(v) + ", f", "var--");
            } else {
                out().insn("movlw", hx(sc));
                out().insn("addwf", varRef(v) + ", f", "var += step");
            }
        } else {
            out().insn("movf", stepReg + ", w");
            out().insn("addwf", varRef(v) + ", f", "var += step");
        }
        out().insn("goto", top);
        out().label(end);
    }

    private void genGoto(Stmt.Goto g) {
        String lbl = symbols.findLabel(g.label);
        if (lbl == null) {
            reporter.error(g.line, g.column, "undefined label '" + g.label + "'");
            return;
        }
        out().insn("goto", lbl);
    }

    private void genGosub(Stmt.Gosub g) {
        String lbl = symbols.findLabel(g.label);
        if (lbl == null) {
            reporter.error(g.line, g.column, "undefined label '" + g.label + "'");
            return;
        }
        out().insn("call", lbl);
    }

    private void genReturn(Stmt.Return r) {
        if (r.value != null) {
            genExprToW(r.value);
        }
        out().insn(core.isBaseline() ? "retlw" : "return", core.isBaseline() ? "0x00" : "");
    }

    private void genCall(Stmt.CallStmt c) {
        Symbols.Sub sub = symbols.findSub(c.name);
        if (sub == null) {
            reporter.error(c.line, c.column, "call to undefined subroutine '" + c.name + "'");
            return;
        }
        if (c.args.size() != sub.params.size()) {
            reporter.error(c.line, c.column, "subroutine '" + c.name + "' expects "
                    + sub.params.size() + " argument(s), got " + c.args.size());
            return;
        }
        for (int i = 0; i < c.args.size(); i++) {
            genExprToW(c.args.get(i));
            String reg = "p_" + sanitize(sub.name) + "_" + sanitize(sub.params.get(i));
            out().insn("movwf", reg, "arg " + sub.params.get(i));
        }
        out().insn("call", sub.label);
    }

    private void genPinOp(Stmt.PinOp p) {
        Resolved r = resolve(p.pin, p.line, p.column);
        if (r.kind != Resolved.Kind.PIN) {
            reporter.error(p.line, p.column, "'" + p.pin + "' is not an I/O pin");
            return;
        }
        bank0();
        switch (p.kind) {
            case HIGH:   out().insn("bsf", r.register + ", " + r.bit, "HIGH " + p.pin); break;
            case LOW:    out().insn("bcf", r.register + ", " + r.bit, "LOW " + p.pin); break;
            case TOGGLE:
                out().insn("movf", r.register + ", w");
                out().insn("xorlw", hx(1 << r.bit), "TOGGLE " + p.pin);
                out().insn("movwf", r.register);
                break;
        }
    }

    private void genDirection(Stmt.Direction d) {
        Resolved r = resolve(d.target, d.line, d.column);
        int mask;
        PortInfo port;
        if (r.kind == Resolved.Kind.PIN) {
            port = r.port;
            mask = 1 << r.bit;
        } else if (r.kind == Resolved.Kind.PORT) {
            port = r.port;
            mask = 0xFF;
        } else {
            reporter.error(d.line, d.column, "'" + d.target + "' is not a pin or port");
            return;
        }
        setDirection(port, mask, r.kind == Resolved.Kind.PIN ? r.bit : -1, d.output);
    }

    private void genDelay(Stmt.Delay d) {
        Integer amount = tryConst(d.amount);
        long perUnit = d.microseconds
                ? Math.max(1, Math.round(config.clockHz / 4_000_000.0))      // cycles per microsecond
                : Math.max(1, Math.round(config.clockHz / 4_000.0));         // cycles per millisecond
        if (amount != null) {
            long cycles = (long) amount * perUnit;
            comment("DELAY " + amount + (d.microseconds ? "us" : "ms")
                    + " (~" + cycles + " cycles @ " + (config.clockHz / 1000) + "kHz)");
            emitCycleDelay(cycles);
        } else {
            // Variable delay: loop 'amount' times over a one-unit busy wait.
            comment("variable DELAY (" + (d.microseconds ? "us" : "ms") + ")");
            genExprToW(d.amount);
            String cnt = "dly_cnt";
            reserveRam(cnt, 1);
            out().insn("movwf", cnt);
            String top = uniqueLabel("dly");
            String skip = uniqueLabel("dlyz");
            out().insn("movf", cnt + ", f");
            out().insn("btfsc", "STATUS, Z");
            out().insn("goto", skip);
            out().label(top);
            emitCycleDelay(perUnit);
            out().insn("decfsz", cnt + ", f");
            out().insn("goto", top);
            out().label(skip);
        }
    }

    // ------------------------------------------------------------------
    // Expression generation (result left in W)
    // ------------------------------------------------------------------

    private void genExprToW(Expr e) {
        if (e instanceof Expr.Num) {
            out().insn("movlw", hx(((Expr.Num) e).value & 0xFF));
        } else if (e instanceof Expr.Name) {
            genName((Expr.Name) e);
        } else if (e instanceof Expr.Peek) {
            genPeek((Expr.Peek) e);
        } else if (e instanceof Expr.AdcRead) {
            genAdcRead((Expr.AdcRead) e);
        } else if (e instanceof Expr.Unary) {
            genUnary((Expr.Unary) e);
        } else if (e instanceof Expr.Binary) {
            genBinary((Expr.Binary) e);
        } else if (e instanceof Expr.Call) {
            genFunctionCall((Expr.Call) e);
        } else {
            reporter.error(e.line, e.column, "internal: unhandled expression");
        }
    }

    private void genName(Expr.Name n) {
        Resolved r = resolve(n.name, n.line, n.column);
        switch (r.kind) {
            case CONST:
                out().insn("movlw", hx(r.value & 0xFF), n.name + " = " + r.value);
                break;
            case VAR:
                out().insn("movf", varRef(r.var) + ", w", "load " + n.name);
                break;
            case PORT:
                bank0();
                out().insn("movf", r.register + ", w", "read " + r.register);
                break;
            case PIN:
                bank0();
                out().insn("clrw");
                out().insn("btfsc", r.register + ", " + r.bit, "read " + n.name);
                out().insn("movlw", "0x01");
                break;
        }
    }

    private void genPeek(Expr.Peek p) {
        Integer addr = tryConst(p.address);
        if (addr == null) {
            reporter.error(p.line, p.column, "PEEK address must be a constant expression");
            return;
        }
        out().insn("movf", hx(addr) + ", w", "PEEK(" + addr + ")");
    }

    private void genUnary(Expr.Unary u) {
        genExprToW(u.operand);
        int t = allocTemp();
        out().insn("movwf", temp(t));
        if (u.op == Expr.Unary.Op.NOT) {
            out().insn("comf", temp(t) + ", w", "NOT");
        } else { // NEG : -x = (~x) + 1
            out().insn("comf", temp(t) + ", f");
            out().insn("incf", temp(t) + ", w", "negate");
        }
        freeTemp();
    }

    private void genBinary(Expr.Binary b) {
        // Constant folding for two literal operands.
        Integer lc = tryConst(b.left), rc = tryConst(b.right);
        if (lc != null && rc != null) {
            out().insn("movlw", hx(foldBinary(b.op, lc, rc) & 0xFF), "folded");
            return;
        }
        genExprToW(b.left);
        int t = allocTemp();
        out().insn("movwf", temp(t), "save lhs");
        genExprToW(b.right); // result in W, lhs preserved in temp(t)

        switch (b.op) {
            case ADD: out().insn("addwf", temp(t) + ", w", "+"); break;
            case SUB: out().insn("subwf", temp(t) + ", w", "lhs - rhs"); break;
            case AND: out().insn("andwf", temp(t) + ", w", "AND"); break;
            case OR:  out().insn("iorwf", temp(t) + ", w", "OR"); break;
            case XOR: out().insn("xorwf", temp(t) + ", w", "XOR"); break;
            case MUL: emitMulDiv("_mul8", t); break;
            case DIV: emitMulDiv("_div8", t); break;
            case MOD: emitMulDiv("_div8", t); out().insn("movf", "rt_rem, w", "remainder"); break;
            case SHL: emitShift(true, b, t); break;
            case SHR: emitShift(false, b, t); break;
            case EQ: case NE: case LT: case LE: case GT: case GE:
                emitCompare(b.op, t); break;
        }
        freeTemp();
    }

    private void emitMulDiv(String routine, int lhsTemp) {
        // W = rhs, temp(lhsTemp) = lhs
        out().insn("movwf", "rt_b", "rhs");
        out().insn("movf", temp(lhsTemp) + ", w");
        out().insn("movwf", "rt_a", "lhs");
        out().insn("call", routine);
        need(routine);
    }

    private void emitShift(boolean left, Expr.Binary b, int lhsTemp) {
        reserveRam("rt_a", 1);
        // lhs currently in temp(lhsTemp); move it into the shift register.
        out().insn("movf", temp(lhsTemp) + ", w");
        out().insn("movwf", "rt_a", "value to shift");
        Integer n = tryConst(b.right);
        if (n != null) {
            int count = n & 0xFF;
            for (int i = 0; i < count; i++) {
                out().insn("bcf", "STATUS, C");
                out().insn(left ? "rlf" : "rrf", "rt_a, f");
            }
        } else {
            reserveRam("rt_cnt", 1);
            genExprToW(b.right);
            out().insn("movwf", "rt_cnt", "shift count");
            String top = uniqueLabel("shf");
            String done = uniqueLabel("shfd");
            out().insn("movf", "rt_cnt, f");
            out().insn("btfsc", "STATUS, Z");
            out().insn("goto", done);
            out().label(top);
            out().insn("bcf", "STATUS, C");
            out().insn(left ? "rlf" : "rrf", "rt_a, f");
            out().insn("decfsz", "rt_cnt, f");
            out().insn("goto", top);
            out().label(done);
        }
        out().insn("movf", "rt_a, w");
    }

    private void emitCompare(Expr.Binary.Op op, int lhsTemp) {
        // temp(lhsTemp) = lhs, W = rhs
        switch (op) {
            case EQ:
                out().insn("subwf", temp(lhsTemp) + ", w", "lhs - rhs");
                out().insn("movlw", "0x00");
                out().insn("btfsc", "STATUS, Z");
                out().insn("movlw", "0x01", "== true");
                break;
            case NE:
                out().insn("subwf", temp(lhsTemp) + ", w");
                out().insn("movlw", "0x00");
                out().insn("btfss", "STATUS, Z");
                out().insn("movlw", "0x01", "<> true");
                break;
            case LT: // lhs < rhs  => no carry from lhs-rhs
                out().insn("subwf", temp(lhsTemp) + ", w", "lhs - rhs");
                out().insn("movlw", "0x00");
                out().insn("btfss", "STATUS, C");
                out().insn("movlw", "0x01", "< true");
                break;
            case GE: // lhs >= rhs => carry set
                out().insn("subwf", temp(lhsTemp) + ", w", "lhs - rhs");
                out().insn("movlw", "0x00");
                out().insn("btfsc", "STATUS, C");
                out().insn("movlw", "0x01", ">= true");
                break;
            case GT: { // lhs > rhs  <=> rhs - lhs has no carry
                int t2 = allocTemp();
                out().insn("movwf", temp(t2), "rhs");
                out().insn("movf", temp(lhsTemp) + ", w");
                out().insn("subwf", temp(t2) + ", w", "rhs - lhs");
                out().insn("movlw", "0x00");
                out().insn("btfss", "STATUS, C");
                out().insn("movlw", "0x01", "> true");
                freeTemp();
                break;
            }
            case LE: { // lhs <= rhs <=> rhs - lhs carry set
                int t2 = allocTemp();
                out().insn("movwf", temp(t2), "rhs");
                out().insn("movf", temp(lhsTemp) + ", w");
                out().insn("subwf", temp(t2) + ", w", "rhs - lhs");
                out().insn("movlw", "0x00");
                out().insn("btfsc", "STATUS, C");
                out().insn("movlw", "0x01", "<= true");
                freeTemp();
                break;
            }
            default: break;
        }
    }

    private void genFunctionCall(Expr.Call c) {
        Symbols.Sub sub = symbols.findSub(c.name);
        if (sub == null) {
            reporter.error(c.line, c.column, "call to undefined function '" + c.name + "'");
            out().insn("clrw");
            return;
        }
        if (!sub.isFunction) {
            reporter.warning(c.line, c.column, "'" + c.name + "' is a SUB used as a function; its return value is undefined");
        }
        if (c.args.size() != sub.params.size()) {
            reporter.error(c.line, c.column, "function '" + c.name + "' expects "
                    + sub.params.size() + " argument(s), got " + c.args.size());
            return;
        }
        for (int i = 0; i < c.args.size(); i++) {
            genExprToW(c.args.get(i));
            String reg = "p_" + sanitize(sub.name) + "_" + sanitize(sub.params.get(i));
            out().insn("movwf", reg, "arg " + sub.params.get(i));
        }
        out().insn("call", sub.label);
    }

    // Branch to 'label' if condition is FALSE (==0).
    private void genCondFalseJump(Expr cond, String label) {
        genExprToW(cond);
        out().insn("iorlw", "0x00", "test condition");
        out().insn("btfsc", "STATUS, Z");
        out().insn("goto", label);
    }

    // Branch to 'label' if condition is TRUE (!=0).
    private void genCondTrueJump(Expr cond, String label) {
        genExprToW(cond);
        out().insn("iorlw", "0x00", "test condition");
        out().insn("btfss", "STATUS, Z");
        out().insn("goto", label);
    }

    // ------------------------------------------------------------------
    // Name resolution
    // ------------------------------------------------------------------

    private static final class Resolved {
        enum Kind { CONST, VAR, PORT, PIN }
        Kind kind;
        int value;            // CONST
        Symbols.Var var;      // VAR
        String register;      // PORT/PIN data register
        int bit;              // PIN
        String pinName;       // PIN
        PortInfo port;        // PORT/PIN
    }

    private Resolved resolve(String name, int line, int col) {
        Resolved r = new Resolved();
        // Local parameter?
        if (localScope != null && localScope.containsKey(name.toUpperCase())) {
            r.kind = Resolved.Kind.VAR;
            r.var = localScope.get(name.toUpperCase());
            return r;
        }
        Symbols.Const c = symbols.findConst(name);
        if (c != null) { r.kind = Resolved.Kind.CONST; r.value = c.value; return r; }
        Symbols.Var v = symbols.findVar(name);
        if (v != null) { r.kind = Resolved.Kind.VAR; r.var = v; return r; }
        // Pin like RB0 / GP2 ?
        if (matchPin(name) != null) {
            r.kind = Resolved.Kind.PIN;
            r.port = pinPort;
            r.register = pinPort.register;
            r.bit = pinBit;
            r.pinName = name;
            return r;
        }
        // Whole port register?
        PortInfo port = chip.portByRegister(name);
        if (port != null) {
            r.kind = Resolved.Kind.PORT;
            r.register = port.register;
            r.port = port;
            return r;
        }
        throw new CompileException("unknown identifier '" + name
                + "' (declare it with DIM, or use a valid pin/port/PEEK)", line, col);
    }

    private PortInfo pinPort;
    private int pinBit;

    /** Returns non-null if name is a pin reference such as RB0; sets pinPort/pinBit. */
    private PortInfo[] matchPin(String name) {
        for (PortInfo p : chip.getPorts()) {
            String pre = p.pinPrefix;
            if (name.length() > pre.length() && name.regionMatches(true, 0, pre, 0, pre.length())) {
                String rest = name.substring(pre.length());
                if (rest.chars().allMatch(Character::isDigit)) {
                    int bit = Integer.parseInt(rest);
                    if (bit < p.pinCount) {
                        pinPort = p;
                        pinBit = bit;
                        return new PortInfo[]{p};
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // I/O direction (TRIS) handling — core/chip aware
    // ------------------------------------------------------------------

    private void setDirection(PortInfo port, int mask, int singleBit, boolean output) {
        comment((output ? "OUTPUT " : "INPUT ") + port.register + (singleBit >= 0 ? ("." + singleBit) : ""));
        if (core.isBaseline() && port.trisRegister == null) {
            // Baseline: TRIS is write-only via the TRIS instruction; keep a shadow
            // copy in RAM and read-modify-write it (0 = output, 1 = input).
            String shadow = "tris_" + port.register.toLowerCase();
            reserveRam(shadow, 1);
            if (mask == 0xFF) {
                out().insn("movlw", output ? "0x00" : "0xFF");
                out().insn("movwf", shadow);
            } else if (output) {
                out().insn("movlw", hx((~mask) & 0xFF));
                out().insn("andwf", shadow + ", f", "clear dir bit -> output");
            } else {
                out().insn("movlw", hx(mask));
                out().insn("iorwf", shadow + ", f", "set dir bit -> input");
            }
            out().insn("movf", shadow + ", w");
            out().insn("tris", String.valueOf(port.trisInstrTarget), "load TRIS");
        } else {
            String tris = port.trisRegister;
            out().insn("banksel", tris);
            if (mask == 0xFF) {
                out().insn(output ? "clrf" : "comf", output ? tris : tris + ", f");
                if (!output) {
                    out().insn("movlw", "0xFF");
                    out().insn("movwf", tris);
                }
            } else if (singleBit >= 0) {
                out().insn(output ? "bcf" : "bsf", tris + ", " + singleBit);
            }
            out().insn("banksel", bank0Anchor);
        }
    }

    private void emitBaselineTrisShadowInit() {
        for (PortInfo p : chip.getPorts()) {
            String shadow = "tris_" + p.register.toLowerCase();
            // Only initialise shadows we actually reserve; reserve all ports' shadows.
            reserveRam(shadow, 1);
            out().insn("movlw", "0xFF", "all inputs at reset");
            out().insn("movwf", shadow);
        }
    }

    private boolean baselineUsesTris() {
        return containsDirection(program.main);
    }

    private boolean containsDirection(List<Stmt> body) {
        for (Stmt s : body) {
            if (s instanceof Stmt.Direction) return true;
            if (s instanceof Stmt.If) {
                Stmt.If i = (Stmt.If) s;
                if (containsDirection(i.thenBody)) return true;
                if (i.elseBody != null && containsDirection(i.elseBody)) return true;
                for (Stmt.ElseIf ei : i.elseIfs) if (containsDirection(ei.body)) return true;
            } else if (s instanceof Stmt.For) {
                if (containsDirection(((Stmt.For) s).body)) return true;
            } else if (s instanceof Stmt.While) {
                if (containsDirection(((Stmt.While) s).body)) return true;
            } else if (s instanceof Stmt.DoLoop) {
                if (containsDirection(((Stmt.DoLoop) s).body)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Peripheral init prologues
    // ------------------------------------------------------------------

    private void emitDigitalIoPrologue() {
        // Banking and the analog/comparator SFRs targeted here are a midrange
        // concern; the baseline parts in our catalogue are plain digital GPIO.
        if (core.isBaseline()) {
            return;
        }
        comment("--- digital I/O setup (disable analog/comparators where present) ---");
        // Disable comparators so PORTA-style pins act as digital I/O.
        out().raw("    ifdef CMCON");
        out().insn("banksel", "CMCON");
        out().insn("movlw", "0x07");
        out().insn("movwf", "CMCON");
        out().raw("    endif");
        out().raw("    ifdef CMCON0");
        out().insn("banksel", "CMCON0");
        out().insn("movlw", "0x07");
        out().insn("movwf", "CMCON0");
        out().raw("    endif");
        if (!usesAdcInProgram()) {
            out().raw("    ifdef ANSEL");
            out().insn("banksel", "ANSEL");
            out().insn("clrf", "ANSEL");
            out().raw("    endif");
            out().raw("    ifdef ANSELH");
            out().insn("banksel", "ANSELH");
            out().insn("clrf", "ANSELH");
            out().raw("    endif");
            out().raw("    ifdef ADCON1");
            out().insn("banksel", "ADCON1");
            out().insn("movlw", "0x06");
            out().insn("movwf", "ADCON1");
            out().raw("    endif");
        }
        out().insn("banksel", bank0Anchor);
    }

    // ------------------------------------------------------------------
    // ADC
    // ------------------------------------------------------------------

    private enum AdcStyle { A877, F675, UNSUPPORTED }

    private AdcStyle adcStyle() {
        String n = chip.getName().toUpperCase();
        if (n.equals("PIC16F877A") || n.equals("PIC16F876A") || n.equals("PIC16F873A")) return AdcStyle.A877;
        if (n.equals("PIC12F675")) return AdcStyle.F675;
        return AdcStyle.UNSUPPORTED;
    }

    private void emitAdcInitPrologue() {
        comment("--- ADC initialisation ---");
        switch (adcStyle()) {
            case A877:
                out().insn("banksel", "ADCON1");
                out().insn("movlw", "0x00", "left justified, all AN pins analog, Vref=Vdd");
                out().insn("movwf", "ADCON1");
                out().insn("banksel", "ADCON0");
                out().insn("movlw", "0x41", "ADCS=01 (Fosc/8), ADON=1");
                out().insn("movwf", "ADCON0");
                out().insn("banksel", bank0Anchor);
                break;
            case F675:
                out().insn("banksel", "ANSEL");
                out().insn("movlw", "0x15", "ADCS=001, AN ch enable (low pins)");
                out().insn("movwf", "ANSEL");
                out().insn("banksel", "ADCON0");
                out().insn("movlw", "0x01", "left justified, ADON=1");
                out().insn("movwf", "ADCON0");
                out().insn("banksel", bank0Anchor);
                break;
            default:
                reporter.warning(0, 0, "ADC code generation is not implemented for "
                        + chip.getName() + "; ADC reads will be left as TODO stubs");
        }
    }

    private void genAdcRead(Expr.AdcRead a) {
        if (!chip.has(Peripheral.ADC)) {
            reporter.error(a.line, a.column, chip.getName() + " has no ADC peripheral");
            out().insn("clrw");
            return;
        }
        Integer ch = tryConst(a.channel);
        if (ch == null) {
            reporter.error(a.line, a.column, "ADC channel must be a constant");
            out().insn("clrw");
            return;
        }
        if (ch >= chip.getAdcChannels()) {
            reporter.error(a.line, a.column, "ADC channel " + ch + " out of range (0.."
                    + (chip.getAdcChannels() - 1) + ")");
        }
        usesAdc = true;
        comment("ADC read channel " + ch);
        switch (adcStyle()) {
            case A877: {
                out().insn("banksel", "ADCON0");
                out().insn("movlw", hx(0x41 | ((ch & 0x07) << 3)), "select channel, ADON");
                out().insn("movwf", "ADCON0");
                emitShortAcqDelay();
                out().insn("bsf", "ADCON0, GO", "start conversion");
                String w = uniqueLabel("adcw");
                out().label(w);
                out().insn("btfsc", "ADCON0, GO", "wait for conversion");
                out().insn("goto", w);
                out().insn("banksel", "ADRESH");
                out().insn("movf", "ADRESH, w", "8-bit result (high byte)");
                out().insn("banksel", bank0Anchor);
                break;
            }
            case F675: {
                out().insn("banksel", "ADCON0");
                out().insn("movlw", hx(0x01 | ((ch & 0x03) << 2)), "select channel, ADON");
                out().insn("movwf", "ADCON0");
                emitShortAcqDelay();
                out().insn("bsf", "ADCON0, GO", "start conversion");
                String w = uniqueLabel("adcw");
                out().label(w);
                out().insn("btfsc", "ADCON0, GO", "wait for conversion");
                out().insn("goto", w);
                out().insn("movf", "ADRESH, w", "8-bit result (high byte)");
                out().insn("banksel", bank0Anchor);
                break;
            }
            default:
                out().insn("clrw");
        }
    }

    private void emitShortAcqDelay() {
        // ~20us acquisition; cheap fixed loop.
        long cyc = Math.max(4, Math.round(config.clockHz / 200_000.0));
        emitCycleDelay(cyc);
    }

    // ------------------------------------------------------------------
    // UART
    // ------------------------------------------------------------------

    private void genUartInit(Stmt.UartInit s) {
        usesUart = true;
        if (config.uartMode == Config.UartMode.HARDWARE) {
            if (!chip.has(Peripheral.UART_HW)) {
                reporter.error(s.line, s.column, chip.getName()
                        + " has no hardware USART; use --uart-mode software");
                return;
            }
            emitHwUartInit();
        } else {
            emitSwUartInit(s);
        }
    }

    private void emitHwUartInit() {
        long baud = config.uartBaud;
        long spbrg = Math.max(0, Math.round(config.clockHz / (16.0 * baud)) - 1);
        comment("hardware USART init @ " + baud + " baud (BRGH=1, SPBRG=" + spbrg + ")");
        out().insn("banksel", "SPBRG");
        out().insn("movlw", hx((int) (spbrg & 0xFF)));
        out().insn("movwf", "SPBRG");
        out().insn("movlw", "0x24", "TXSTA: BRGH=1, TXEN=1, async");
        out().insn("movwf", "TXSTA");
        out().insn("banksel", "RCSTA");
        out().insn("movlw", "0x90", "RCSTA: SPEN=1, CREN=1");
        out().insn("movwf", "RCSTA");
        out().insn("banksel", bank0Anchor);
        need("_uart_tx_hw");
    }

    private void emitSwUartInit(Stmt.UartInit s) {
        PortInfo[] tx = matchPin(config.uartTxPin);
        if (tx == null) {
            reporter.error(s.line, s.column, "software UART TX pin '" + config.uartTxPin
                    + "' is not valid for " + chip.getName());
            return;
        }
        PortInfo txPort = pinPort;
        int txBit = pinBit;
        comment("software UART init: TX=" + config.uartTxPin + " @ " + config.uartBaud + " baud");
        // Idle high + set as output.
        bank0();
        out().insn("bsf", txPort.register + ", " + txBit, "TX idle high");
        setDirection(txPort, 1 << txBit, txBit, true);
        need("_uart_tx_sw");
        swUartTxPort = txPort;
        swUartTxBit = txBit;
    }

    private PortInfo swUartTxPort;
    private int swUartTxBit;

    private void genUartWrite(Stmt.UartWrite s) {
        usesUart = true;
        genExprToW(s.value);
        out().insn("call", config.uartMode == Config.UartMode.HARDWARE ? "_uart_tx_hw" : "_uart_tx_sw");
        need(config.uartMode == Config.UartMode.HARDWARE ? "_uart_tx_hw" : "_uart_tx_sw");
    }

    private void genPrint(Stmt.Print p) {
        usesUart = true;
        if (core.isBaseline()) {
            reporter.warning(p.line, p.column,
                    "PRINT on the baseline core may exceed the 2-level hardware call stack; verify stack usage");
        }
        String txRoutine = config.uartMode == Config.UartMode.HARDWARE ? "_uart_tx_hw" : "_uart_tx_sw";
        for (Stmt.Print.Item item : p.items) {
            if (item.text != null) {
                for (int i = 0; i < item.text.length(); i++) {
                    out().insn("movlw", hx(item.text.charAt(i) & 0xFF), "'" + safeChar(item.text.charAt(i)) + "'");
                    out().insn("call", txRoutine);
                }
                need(txRoutine);
            } else {
                genExprToW(item.expr);
                out().insn("call", "_print_dec");
                need("_print_dec");
                need(txRoutine);
                need("_div8");
            }
        }
        if (p.newline) {
            out().insn("movlw", "0x0D", "CR");
            out().insn("call", txRoutine);
            out().insn("movlw", "0x0A", "LF");
            out().insn("call", txRoutine);
        }
    }

    private void genPoke(Stmt.Poke p) {
        genExprToW(p.value);
        out().insn("banksel", p.register);
        out().insn("movwf", p.register, "POKE " + p.register);
        out().insn("banksel", bank0Anchor);
    }

    // ------------------------------------------------------------------
    // Runtime library
    // ------------------------------------------------------------------

    private void need(String routine) {
        emittedRoutines.add(routine);
    }

    private void flushRuntime() {
        if (emittedRoutines.contains("_mul8")) emitMul8();
        if (emittedRoutines.contains("_div8")) emitDiv8();
        if (emittedRoutines.contains("_uart_tx_sw")) emitSwUartTx();
        if (emittedRoutines.contains("_uart_tx_hw")) emitHwUartTx();
        if (emittedRoutines.contains("_print_dec")) emitPrintDec();
    }

    private String ret() { return core.isBaseline() ? "retlw" : "return"; }
    private String retOp() { return core.isBaseline() ? "0x00" : ""; }
    private void emitReturn(AsmBuilder b) { b.insn(ret(), retOp()); }

    private void emitMul8() {
        reserveRam("rt_a", 1); reserveRam("rt_b", 1); reserveRam("rt_res", 1); reserveRam("rt_cnt", 1);
        bRuntime.blank();
        bRuntime.comment("8x8 multiply (low byte): W = rt_a * rt_b");
        bRuntime.label("_mul8");
        bRuntime.insn("clrf", "rt_res");
        bRuntime.insn("movf", "rt_b, w");
        bRuntime.insn("movwf", "rt_cnt");
        bRuntime.label("_mul8_loop");
        bRuntime.insn("movf", "rt_cnt, f");
        bRuntime.insn("btfsc", "STATUS, Z");
        bRuntime.insn("goto", "_mul8_done");
        bRuntime.insn("movf", "rt_a, w");
        bRuntime.insn("addwf", "rt_res, f");
        bRuntime.insn("decf", "rt_cnt, f");
        bRuntime.insn("goto", "_mul8_loop");
        bRuntime.label("_mul8_done");
        bRuntime.insn("movf", "rt_res, w");
        emitReturn(bRuntime);
    }

    private void emitDiv8() {
        reserveRam("rt_a", 1); reserveRam("rt_b", 1); reserveRam("rt_quot", 1); reserveRam("rt_rem", 1);
        bRuntime.blank();
        bRuntime.comment("8/8 divide: W = rt_a / rt_b (quotient), rt_rem = remainder");
        bRuntime.label("_div8");
        bRuntime.insn("clrf", "rt_quot");
        bRuntime.insn("movf", "rt_b, f");
        bRuntime.insn("btfsc", "STATUS, Z");
        bRuntime.insn("goto", "_div8_zero");
        bRuntime.insn("movf", "rt_a, w");
        bRuntime.insn("movwf", "rt_rem");
        bRuntime.label("_div8_loop");
        bRuntime.insn("movf", "rt_b, w");
        bRuntime.insn("subwf", "rt_rem, w", "rem - b");
        bRuntime.insn("btfss", "STATUS, C");
        bRuntime.insn("goto", "_div8_done");
        bRuntime.insn("movwf", "rt_rem");
        bRuntime.insn("incf", "rt_quot, f");
        bRuntime.insn("goto", "_div8_loop");
        bRuntime.label("_div8_done");
        bRuntime.insn("movf", "rt_quot, w");
        emitReturn(bRuntime);
        bRuntime.label("_div8_zero");
        bRuntime.insn("movlw", "0xFF");
        bRuntime.insn("movwf", "rt_quot");
        bRuntime.insn("clrf", "rt_rem");
        bRuntime.insn("movf", "rt_quot, w");
        emitReturn(bRuntime);
    }

    private void emitSwUartTx() {
        reserveRam("rt_uart_data", 1); reserveRam("rt_uart_bits", 1);
        reserveRam("rt_d0", 1); reserveRam("rt_d1", 1);
        long bitCycles = Math.max(1, tcyHz / Math.max(1, config.uartBaud));
        PortInfo port = swUartTxPort != null ? swUartTxPort : chip.getPorts().get(0);
        int bit = swUartTxBit;
        bRuntime.blank();
        bRuntime.comment("software UART transmit (8N1) of W on " + port.register + "." + bit
                + " @ " + config.uartBaud + " baud (~" + bitCycles + " cyc/bit)");
        bRuntime.label("_uart_tx_sw");
        bRuntime.insn("movwf", "rt_uart_data");
        bRuntime.insn("bcf", port.register + ", " + bit, "start bit");
        emitInlineDelay(bRuntime, bitCycles, "_utxd");
        bRuntime.insn("movlw", "0x08");
        bRuntime.insn("movwf", "rt_uart_bits");
        bRuntime.label("_uart_tx_sw_bit");
        bRuntime.insn("rrf", "rt_uart_data, f", "LSB -> C");
        bRuntime.insn("btfsc", "STATUS, C");
        bRuntime.insn("bsf", port.register + ", " + bit);
        bRuntime.insn("btfss", "STATUS, C");
        bRuntime.insn("bcf", port.register + ", " + bit);
        emitInlineDelay(bRuntime, bitCycles, "_utxb");
        bRuntime.insn("decfsz", "rt_uart_bits, f");
        bRuntime.insn("goto", "_uart_tx_sw_bit");
        bRuntime.insn("bsf", port.register + ", " + bit, "stop bit");
        emitInlineDelay(bRuntime, bitCycles, "_utxs");
        emitReturn(bRuntime);
    }

    private void emitHwUartTx() {
        bRuntime.blank();
        bRuntime.comment("hardware USART transmit of W");
        bRuntime.label("_uart_tx_hw");
        bRuntime.insn("banksel", "TXSTA");
        bRuntime.label("_uart_tx_hw_wait");
        bRuntime.insn("btfss", "TXSTA, TRMT", "wait for empty shift reg");
        bRuntime.insn("goto", "_uart_tx_hw_wait");
        bRuntime.insn("banksel", "TXREG");
        bRuntime.insn("movwf", "TXREG");
        bRuntime.insn("banksel", bank0Anchor);
        emitReturn(bRuntime);
    }

    private void emitPrintDec() {
        reserveRam("rt_dec_val", 1); reserveRam("rt_dec_d", 1); reserveRam("rt_dec_started", 1);
        reserveRam("rt_a", 1); reserveRam("rt_b", 1); reserveRam("rt_quot", 1); reserveRam("rt_rem", 1);
        String tx = config.uartMode == Config.UartMode.HARDWARE ? "_uart_tx_hw" : "_uart_tx_sw";
        bRuntime.blank();
        bRuntime.comment("print W as unsigned decimal (leading zeros suppressed)");
        bRuntime.label("_print_dec");
        bRuntime.insn("movwf", "rt_dec_val");
        bRuntime.insn("clrf", "rt_dec_started");
        // hundreds
        emitPrintDigit(100, tx, false);
        // tens
        emitPrintDigit(10, tx, false);
        // ones (always)
        bRuntime.insn("movf", "rt_dec_val, w");
        bRuntime.insn("movwf", "rt_dec_d");
        bRuntime.insn("bsf", "rt_dec_started, 0");
        emitPrintDigitOut(tx);
        emitReturn(bRuntime);
    }

    private int printDigitSeq = 0;
    private void emitPrintDigit(int divisor, String tx, boolean alwaysPrint) {
        bRuntime.insn("movf", "rt_dec_val, w");
        bRuntime.insn("movwf", "rt_a");
        bRuntime.insn("movlw", hx(divisor));
        bRuntime.insn("movwf", "rt_b");
        bRuntime.insn("call", "_div8", "digit = val / " + divisor);
        bRuntime.insn("movwf", "rt_dec_d");
        bRuntime.insn("movf", "rt_rem, w");
        bRuntime.insn("movwf", "rt_dec_val", "val = val % " + divisor);
        emitPrintDigitOut(tx);
    }

    private void emitPrintDigitOut(String tx) {
        String skip = "_pd_skip" + (printDigitSeq);
        String prnt = "_pd_print" + (printDigitSeq);
        printDigitSeq++;
        bRuntime.insn("movf", "rt_dec_d, f");
        bRuntime.insn("btfss", "STATUS, Z");
        bRuntime.insn("goto", prnt, "non-zero digit");
        bRuntime.insn("btfsc", "rt_dec_started, 0");
        bRuntime.insn("goto", prnt, "already printing");
        bRuntime.insn("goto", skip, "suppress leading zero");
        bRuntime.label(prnt);
        bRuntime.insn("bsf", "rt_dec_started, 0");
        bRuntime.insn("movlw", "0x30", "'0'");
        bRuntime.insn("addwf", "rt_dec_d, w", "digit -> ASCII");
        bRuntime.insn("call", tx);
        bRuntime.label(skip);
    }

    // ------------------------------------------------------------------
    // Delay generation
    // ------------------------------------------------------------------

    private void emitCycleDelay(long cycles) {
        emitInlineDelay(out(), cycles, "dl");
    }

    /** Emit a busy-wait of approximately {@code cycles} instruction cycles. */
    private void emitInlineDelay(AsmBuilder b, long cycles, String prefix) {
        if (cycles < 6) {
            for (long i = 0; i < cycles; i++) b.insn("nop");
            return;
        }
        long iters = cycles / 3;            // inner loop ~3 cycles/iteration
        if (iters < 1) iters = 1;
        // Balance the loop counts so c0*c1*c2 ~= iters with minimal overshoot,
        // adding levels only as the count grows (each count is 1..256, 256 == 0x00).
        int c0, c1, c2;
        if (iters <= 256) {
            c0 = (int) iters; c1 = 1; c2 = 1;
        } else if (iters <= 256L * 256) {
            c1 = clamp256((long) Math.round(Math.sqrt(iters)));
            c0 = clamp256(Math.round((double) iters / c1));
            c2 = 1;
        } else {
            c2 = clamp256((long) Math.round(Math.cbrt(iters)));
            c1 = clamp256((long) Math.round(Math.sqrt((double) iters / c2)));
            c0 = clamp256(Math.round((double) iters / ((double) c1 * c2)));
        }

        String l0 = prefix + (labelSeq) + "_0";
        String l1 = prefix + (labelSeq) + "_1";
        String l2 = prefix + (labelSeq++) + "_2";
        reserveRam("rt_d0", 1);
        if (c1 > 1) reserveRam("rt_d1", 1);
        if (c2 > 1) reserveRam("rt_d2", 1);

        if (c2 > 1) {
            b.insn("movlw", hx(c2 & 0xFF));
            b.insn("movwf", "rt_d2");
            b.label(l2);
        }
        if (c1 > 1) {
            b.insn("movlw", hx(c1 & 0xFF));
            b.insn("movwf", "rt_d1");
            b.label(l1);
        }
        b.insn("movlw", hx(c0 & 0xFF));
        b.insn("movwf", "rt_d0");
        b.label(l0);
        b.insn("decfsz", "rt_d0, f");
        b.insn("goto", l0);
        if (c1 > 1) {
            b.insn("decfsz", "rt_d1, f");
            b.insn("goto", l1);
        }
        if (c2 > 1) {
            b.insn("decfsz", "rt_d2, f");
            b.insn("goto", l2);
        }
    }

    // ------------------------------------------------------------------
    // Final assembly
    // ------------------------------------------------------------------

    private Result assemble() {
        AsmBuilder head = new AsmBuilder(config.comments);
        emitHeader(head);
        emitConfig(head);
        List<String> mapLines = emitRamBlock(head);
        emitVectors(head);

        AsmBuilder file = new AsmBuilder(config.comments);
        for (String l : head.lines()) file.raw(l);
        file.blank();
        for (String l : bMain.lines()) file.raw(l);
        for (String l : bSubs.lines()) file.raw(l);
        if (!bRuntime.lines().isEmpty()) {
            file.blank();
            file.banner("==================== runtime support ====================");
            for (String l : bRuntime.lines()) file.raw(l);
        }
        file.blank();
        file.directive("        END");

        int words = estimateWords(file.lines());
        if (words > chip.getProgramWords()) {
            reporter.warning(0, 0, "estimated program size " + words + " words exceeds "
                    + chip.getName() + " capacity of " + chip.getProgramWords() + " words");
        }
        return new Result(file.toString(), mapLines, words);
    }

    private void emitHeader(AsmBuilder b) {
        b.banner("================================================================");
        b.banner(" Generated by basic2asm  -  BASIC -> MPASM transpiler");
        b.banner(" Target chip : " + chip.getName() + "  (" + core + " core)");
        b.banner(" Clock       : " + (config.clockHz / 1000) + " kHz");
        b.banner(" Optimize    : " + config.optimize);
        b.banner(" Flash       : " + chip.getProgramWords() + " words   RAM: "
                + chip.getGprBytes() + " bytes");
        b.banner("================================================================");
        b.blank();
        b.directive("        LIST    p=" + chip.getMpasmProcessor());
        b.directive("        #include <" + chip.getIncludeFile() + ">");
        b.blank();
    }

    private void emitConfig(AsmBuilder b) {
        String cfg = config.rawConfig;
        if (cfg == null) {
            String oscToken = selectOscToken();
            cfg = oscToken;
            if (chip.getBaseConfigTokens() != null && !chip.getBaseConfigTokens().isEmpty()) {
                cfg = oscToken + " & " + chip.getBaseConfigTokens();
            }
        }
        b.comment("fuse configuration (config word @ 0x" + Integer.toHexString(chip.getConfigAddress()) + ")");
        b.directive("        __CONFIG " + cfg);
        b.blank();
    }

    private String selectOscToken() {
        String mode = config.oscMode;
        if (mode == null) {
            if (config.clockHz <= 200_000L) mode = "LP";
            else if (config.clockHz <= 4_000_000L) mode = "XT";
            else mode = "HS";
        }
        Map<String, String> toks = chip.getOscTokens();
        String token = toks.get(mode);
        if (token == null) {
            // Fall back to any available token.
            if (!toks.isEmpty()) {
                token = toks.values().iterator().next();
                reporter.warning(0, 0, "oscillator mode '" + mode + "' not listed for "
                        + chip.getName() + "; using " + token);
            } else {
                token = "_XT_OSC";
            }
        }
        return token;
    }

    private List<String> emitRamBlock(AsmBuilder b) {
        List<String> mapLines = new ArrayList<>();
        // Append temporaries last.
        for (int i = 0; i < tempMax; i++) {
            reserveRam("tmp" + i, 1);
        }
        b.comment("--- RAM allocation (general purpose registers) ---");
        b.directive("        cblock  0x" + Integer.toHexString(chip.getGprStart()).toUpperCase());
        int addr = chip.getGprStart();
        for (Map.Entry<String, Integer> e : ram.entrySet()) {
            int size = e.getValue();
            if (size > 1) {
                b.directive("            " + e.getKey() + ":" + size);
            } else {
                b.directive("            " + e.getKey());
            }
            mapLines.add(String.format("0x%02X  %-16s (%d byte%s)", addr, e.getKey(), size, size == 1 ? "" : "s"));
            addr += size;
        }
        b.directive("        endc");
        b.blank();
        int used = addr - chip.getGprStart();
        if (addr - 1 > chip.getGprEnd()) {
            reporter.error(0, 0, "out of RAM on " + chip.getName() + ": need " + used
                    + " bytes but only " + chip.getGprBytes() + " are available");
        }
        return mapLines;
    }

    private void emitVectors(AsmBuilder b) {
        b.comment("--- reset / interrupt vectors ---");
        b.directive("        org     0x0000");
        if (chip.getProgramWords() > 2048 && core.isMidrange()) {
            b.insn("pagesel", "main");
        }
        b.insn("goto", "main");
        if (core.isMidrange()) {
            b.blank();
            b.directive("        org     0x0004");
            if (hasIsr) {
                for (String l : bIsr.lines()) b.raw(l);
            } else {
                b.insn("retfie", "", "no ISR defined");
            }
        }
        b.blank();
    }

    /** Generate the interrupt handler (if defined) into its own buffer. */
    private void generateIsr() {
        if (!hasIsr || core.isBaseline()) {
            return;
        }
        reserveRam("w_temp", 1);
        reserveRam("status_temp", 1);
        Stmt.SubDecl isr = null;
        for (Stmt.SubDecl s : program.subs) {
            if (s.name.equalsIgnoreCase("ISR")) { isr = s; break; }
        }
        bIsr.comment("interrupt service routine");
        bIsr.label("__isr");
        bIsr.insn("movwf", "w_temp", "save context");
        bIsr.insn("swapf", "STATUS, w");
        bIsr.insn("movwf", "status_temp");
        AsmBuilder prev = currentTarget;
        currentTarget = bIsr;
        scanLabels(isr.body);
        genBlock(isr.body);
        currentTarget = prev;
        bIsr.insn("swapf", "status_temp, w", "restore context");
        bIsr.insn("movwf", "STATUS");
        bIsr.insn("swapf", "w_temp, f");
        bIsr.insn("swapf", "w_temp, w");
        bIsr.insn("retfie");
    }

    /** Rough flash usage estimate: count emitted machine instructions. */
    private int estimateWords(List<String> lines) {
        int n = 0;
        for (String l : lines) {
            // Instructions are emitted indented; directives/labels start at column 0.
            if (!l.startsWith("        ")) continue;
            String t = l.trim();
            if (t.isEmpty() || t.startsWith(";")) continue;
            String mn = t.split("\\s+", 2)[0].toLowerCase();
            if (mn.equals("list") || mn.equals("org") || mn.equals("__config")
                    || mn.equals("cblock") || mn.equals("endc") || mn.equals("end")
                    || mn.equals("#include") || mn.equals("banksel")) {
                // banksel expands to 0-2 instructions; approximate as 1
                if (mn.equals("banksel")) n++;
                continue;
            }
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private boolean usesAdcInProgram() {
        return exprUsesAdc(program.main) || subsUseAdc();
    }

    private boolean subsUseAdc() {
        for (Stmt.SubDecl s : program.subs) {
            if (exprUsesAdc(s.body)) return true;
        }
        return false;
    }

    private boolean exprUsesAdc(List<Stmt> body) {
        for (Stmt s : body) {
            if (s instanceof Stmt.Assign && containsAdc(((Stmt.Assign) s).value)) return true;
            if (s instanceof Stmt.Dim && ((Stmt.Dim) s).init != null && containsAdc(((Stmt.Dim) s).init)) return true;
            if (s instanceof Stmt.If) {
                Stmt.If i = (Stmt.If) s;
                if (containsAdc(i.cond) || exprUsesAdc(i.thenBody)
                        || (i.elseBody != null && exprUsesAdc(i.elseBody))) return true;
                for (Stmt.ElseIf ei : i.elseIfs) if (containsAdc(ei.cond) || exprUsesAdc(ei.body)) return true;
            } else if (s instanceof Stmt.For)    { if (exprUsesAdc(((Stmt.For) s).body)) return true; }
            else if (s instanceof Stmt.While)    { if (exprUsesAdc(((Stmt.While) s).body)) return true; }
            else if (s instanceof Stmt.DoLoop)   { if (exprUsesAdc(((Stmt.DoLoop) s).body)) return true; }
            else if (s instanceof Stmt.UartWrite){ if (containsAdc(((Stmt.UartWrite) s).value)) return true; }
            else if (s instanceof Stmt.Print) {
                for (Stmt.Print.Item it : ((Stmt.Print) s).items)
                    if (it.expr != null && containsAdc(it.expr)) return true;
            }
        }
        return false;
    }

    private boolean containsAdc(Expr e) {
        if (e instanceof Expr.AdcRead) return true;
        if (e instanceof Expr.Unary) return containsAdc(((Expr.Unary) e).operand);
        if (e instanceof Expr.Binary) return containsAdc(((Expr.Binary) e).left) || containsAdc(((Expr.Binary) e).right);
        if (e instanceof Expr.Peek) return containsAdc(((Expr.Peek) e).address);
        if (e instanceof Expr.Call) {
            for (Expr a : ((Expr.Call) e).args) if (containsAdc(a)) return true;
        }
        return false;
    }

    private Symbols.Var defineVar(String name, VarType type) {
        String asm = "v_" + sanitize(name);
        Symbols.Var v = new Symbols.Var(name, type, asm);
        symbols.addVar(v);
        reserveRam(asm, type.bytes);
        return v;
    }

    private String varRef(Symbols.Var v) {
        return v.asmName;
    }

    private void storeWToVar(Symbols.Var v) {
        out().insn("movwf", v.asmName);
    }

    private void reserveRam(String name, int size) {
        if (!ram.containsKey(name)) {
            ram.put(name, size);
        }
    }

    private void bank0() {
        if (core.isMidrange()) {
            out().insn("banksel", bank0Anchor);
        }
    }

    private int allocTemp() {
        int idx = tempTop++;
        if (tempTop > tempMax) tempMax = tempTop;
        return idx;
    }

    private void freeTemp() {
        if (tempTop > 0) tempTop--;
    }

    private String temp(int i) {
        return "tmp" + i;
    }

    private String uniqueLabel(String prefix) {
        return prefix + "_" + (labelSeq++);
    }

    private void comment(String c) {
        out().comment(c);
    }

    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.toString();
    }

    private static String hx(int v) {
        return String.format("0x%02X", v & 0xFF);
    }

    /** Clamp a loop count to the representable 1..256 range (256 is loaded as 0x00). */
    private static int clamp256(long v) {
        if (v < 1) return 1;
        if (v > 256) return 256;
        return (int) v;
    }

    private static String safeChar(char c) {
        return (c >= 32 && c < 127) ? String.valueOf(c) : "?";
    }

    private Integer tryConst(Expr e) {
        try {
            return evalConst(e);
        } catch (CompileException ex) {
            return null;
        }
    }

    /** Evaluate a compile-time-constant expression, throwing if not constant. */
    private int evalConst(Expr e) {
        if (e instanceof Expr.Num) {
            return ((Expr.Num) e).value;
        }
        if (e instanceof Expr.Name) {
            Symbols.Const c = symbols.findConst(((Expr.Name) e).name);
            if (c != null) return c.value;
            throw new CompileException("not a constant", e.line, e.column);
        }
        if (e instanceof Expr.Unary) {
            Expr.Unary u = (Expr.Unary) e;
            int v = evalConst(u.operand);
            return u.op == Expr.Unary.Op.NEG ? -v : ~v;
        }
        if (e instanceof Expr.Binary) {
            Expr.Binary b = (Expr.Binary) e;
            return foldBinary(b.op, evalConst(b.left), evalConst(b.right));
        }
        throw new CompileException("not a constant", e.line, e.column);
    }

    private int foldBinary(Expr.Binary.Op op, int l, int r) {
        switch (op) {
            case ADD: return l + r;
            case SUB: return l - r;
            case MUL: return l * r;
            case DIV: return r == 0 ? 0xFF : l / r;
            case MOD: return r == 0 ? 0 : l % r;
            case AND: return l & r;
            case OR:  return l | r;
            case XOR: return l ^ r;
            case SHL: return l << r;
            case SHR: return (l & 0xFF) >> r;
            case EQ:  return l == r ? 1 : 0;
            case NE:  return l != r ? 1 : 0;
            case LT:  return l < r ? 1 : 0;
            case LE:  return l <= r ? 1 : 0;
            case GT:  return l > r ? 1 : 0;
            case GE:  return l >= r ? 1 : 0;
            default:  return 0;
        }
    }
}
