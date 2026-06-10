package org.basic2asm.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * The root of a parsed BASIC program. Top-level subroutine/function declarations
 * are separated from the main "module" statements so the generator can lay out
 * the reset vector, main code and subroutine bodies in distinct sections.
 */
public final class Program {
    public final List<Stmt> main = new ArrayList<>();
    public final List<Stmt.SubDecl> subs = new ArrayList<>();
}
