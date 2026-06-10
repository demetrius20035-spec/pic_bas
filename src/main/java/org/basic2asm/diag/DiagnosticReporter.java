package org.basic2asm.diag;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects diagnostics produced across all compiler phases and renders them
 * with the offending source line, mimicking the style of common compilers.
 */
public final class DiagnosticReporter {

    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final String[] sourceLines;
    private final String sourceName;

    public DiagnosticReporter(String sourceName, String source) {
        this.sourceName = sourceName == null ? "<input>" : sourceName;
        this.sourceLines = source == null ? new String[0] : source.split("\n", -1);
    }

    public void error(int line, int column, String message) {
        diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, line, column, message));
    }

    public void warning(int line, int column, String message) {
        diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, line, column, message));
    }

    public void info(int line, int column, String message) {
        diagnostics.add(new Diagnostic(Diagnostic.Severity.INFO, line, column, message));
    }

    public boolean hasErrors() {
        for (Diagnostic d : diagnostics) {
            if (d.getSeverity() == Diagnostic.Severity.ERROR) {
                return true;
            }
        }
        return false;
    }

    public int errorCount() {
        int n = 0;
        for (Diagnostic d : diagnostics) {
            if (d.getSeverity() == Diagnostic.Severity.ERROR) n++;
        }
        return n;
    }

    public int warningCount() {
        int n = 0;
        for (Diagnostic d : diagnostics) {
            if (d.getSeverity() == Diagnostic.Severity.WARNING) n++;
        }
        return n;
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    /** Render all diagnostics to the given stream, each with its source line. */
    public void printAll(PrintStream out) {
        for (Diagnostic d : diagnostics) {
            out.println(sourceName + ":" + d);
            int idx = d.getLine() - 1;
            if (idx >= 0 && idx < sourceLines.length) {
                String text = sourceLines[idx];
                out.println("    " + text);
                if (d.getColumn() > 0) {
                    StringBuilder caret = new StringBuilder("    ");
                    for (int i = 1; i < d.getColumn() && i <= text.length(); i++) {
                        caret.append(text.charAt(i - 1) == '\t' ? '\t' : ' ');
                    }
                    caret.append('^');
                    out.println(caret.toString());
                }
            }
        }
        if (!diagnostics.isEmpty()) {
            out.println(errorCount() + " error(s), " + warningCount() + " warning(s).");
        }
    }
}
