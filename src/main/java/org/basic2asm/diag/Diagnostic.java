package org.basic2asm.diag;

/**
 * A single compiler message (error or warning) tied to a source location.
 */
public final class Diagnostic {

    public enum Severity { ERROR, WARNING, INFO }

    private final Severity severity;
    private final int line;
    private final int column;
    private final String message;

    public Diagnostic(Severity severity, int line, int column, String message) {
        this.severity = severity;
        this.line = line;
        this.column = column;
        this.message = message;
    }

    public Severity getSeverity() { return severity; }
    public int getLine()          { return line; }
    public int getColumn()        { return column; }
    public String getMessage()    { return message; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(severity.name().toLowerCase());
        if (line > 0) {
            sb.append(" [line ").append(line);
            if (column > 0) {
                sb.append(", col ").append(column);
            }
            sb.append(']');
        }
        sb.append(": ").append(message);
        return sb.toString();
    }
}
