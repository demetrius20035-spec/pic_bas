package org.basic2asm.lexer;

/**
 * A lexical token with its source position (1-based line and column) so that
 * later phases can attach diagnostics to the exact spot in the source.
 */
public final class Token {

    public final TokenType type;
    public final String text;
    public final int intValue;   // valid for NUMBER tokens
    public final int line;
    public final int column;

    public Token(TokenType type, String text, int intValue, int line, int column) {
        this.type = type;
        this.text = text;
        this.intValue = intValue;
        this.line = line;
        this.column = column;
    }

    public boolean is(TokenType t) { return type == t; }

    @Override
    public String toString() {
        return type + "('" + text + "')@" + line + ":" + column;
    }
}
