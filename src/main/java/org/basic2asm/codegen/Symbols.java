package org.basic2asm.codegen;

import org.basic2asm.ast.Stmt;
import org.basic2asm.ast.VarType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Symbol table for a compilation: constants (compile-time folded), variables
 * (assigned RAM) and subroutines/functions. Also tracks the user labels that
 * have been declared so the generator can validate GOTO/GOSUB targets.
 */
public final class Symbols {

    public static final class Const {
        public final String name;
        public final int value;
        public Const(String name, int value) { this.name = name; this.value = value; }
    }

    public static final class Var {
        public final String name;
        public final VarType type;
        public final String asmName;  // generated assembler symbol
        public Var(String name, VarType type, String asmName) {
            this.name = name; this.type = type; this.asmName = asmName;
        }
    }

    public static final class Sub {
        public final String name;
        public final boolean isFunction;
        public final List<String> params;
        public final String label;
        public final Stmt.SubDecl decl;
        public Sub(String name, boolean isFunction, List<String> params, String label, Stmt.SubDecl decl) {
            this.name = name; this.isFunction = isFunction; this.params = params;
            this.label = label; this.decl = decl;
        }
    }

    private final Map<String, Const> consts = new LinkedHashMap<>();
    private final Map<String, Var> vars = new LinkedHashMap<>();
    private final Map<String, Sub> subs = new LinkedHashMap<>();
    private final Map<String, String> labels = new LinkedHashMap<>(); // user label -> asm label

    public void addConst(String name, int value) { consts.put(key(name), new Const(name, value)); }
    public Const findConst(String name)          { return consts.get(key(name)); }

    public void addVar(Var v)            { vars.put(key(v.name), v); }
    public Var findVar(String name)      { return vars.get(key(name)); }
    public Map<String, Var> vars()       { return vars; }

    public void addSub(Sub s)            { subs.put(key(s.name), s); }
    public Sub findSub(String name)      { return subs.get(key(name)); }
    public Map<String, Sub> subs()       { return subs; }

    public void addLabel(String user, String asm) { labels.put(key(user), asm); }
    public String findLabel(String user)           { return labels.get(key(user)); }

    private static String key(String name) { return name.toUpperCase(); }
}
