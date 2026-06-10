package org.basic2asm.codegen;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates formatted MPASM source lines. Provides consistent column
 * alignment (label / mnemonic / operand / comment) and optional debug comments
 * controlled by the {@code --comments} option.
 */
public final class AsmBuilder {

    private final List<String> lines = new ArrayList<>();
    private final boolean comments;

    public AsmBuilder(boolean comments) {
        this.comments = comments;
    }

    /** A label definition at column 0. */
    public void label(String name) {
        lines.add(name);
    }

    /** An instruction with no operand. */
    public void insn(String mnemonic) {
        lines.add("        " + mnemonic);
    }

    /** An instruction with an operand. */
    public void insn(String mnemonic, String operand) {
        lines.add(String.format("        %-7s %s", mnemonic, operand));
    }

    /** An instruction with an operand and an inline comment (if comments enabled). */
    public void insn(String mnemonic, String operand, String comment) {
        if (comments && comment != null) {
            lines.add(String.format("        %-7s %-18s ; %s", mnemonic, operand, comment));
        } else {
            insn(mnemonic, operand);
        }
    }

    /** A raw directive line (e.g. cblock, __CONFIG) placed at column 0. */
    public void directive(String text) {
        lines.add(text);
    }

    /** A full-line comment (only emitted when comments are enabled). */
    public void comment(String text) {
        if (comments) {
            lines.add("; " + text);
        }
    }

    /** A banner comment that is always emitted regardless of the comments flag. */
    public void banner(String text) {
        lines.add("; " + text);
    }

    public void blank() {
        lines.add("");
    }

    public void raw(String line) {
        lines.add(line);
    }

    public List<String> lines() {
        return lines;
    }

    public boolean commentsEnabled() {
        return comments;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
