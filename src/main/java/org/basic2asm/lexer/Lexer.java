package org.basic2asm.lexer;

import org.basic2asm.diag.DiagnosticReporter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts BASIC source text into a flat list of {@link Token}s.
 *
 * <p>The dialect is line-oriented: NEWLINE tokens are significant (they end
 * statements). Comments start with {@code REM} or an apostrophe and run to the
 * end of the line. Numbers may be decimal, hex ({@code &Hxx} or {@code 0x..}),
 * binary ({@code &Bxx} or {@code %..}) or a character literal ({@code 'A'} is a
 * comment, so character constants use double-quote-free {@code ASC} — instead we
 * support the BASIC-style backtick-free char via the {@code ASC()} builtin}).</p>
 */
public final class Lexer {

    private static final Map<String, TokenType> KEYWORDS = new HashMap<>();
    static {
        KEYWORDS.put("DIM", TokenType.DIM);
        KEYWORDS.put("AS", TokenType.AS);
        KEYWORDS.put("CONST", TokenType.CONST);
        KEYWORDS.put("LET", TokenType.LET);
        KEYWORDS.put("BYTE", TokenType.BYTE);
        KEYWORDS.put("WORD", TokenType.WORD);
        KEYWORDS.put("BIT", TokenType.BIT);
        KEYWORDS.put("IF", TokenType.IF);
        KEYWORDS.put("THEN", TokenType.THEN);
        KEYWORDS.put("ELSE", TokenType.ELSE);
        KEYWORDS.put("ELSEIF", TokenType.ELSEIF);
        KEYWORDS.put("ENDIF", TokenType.ENDIF);
        KEYWORDS.put("END", TokenType.END);
        KEYWORDS.put("FOR", TokenType.FOR);
        KEYWORDS.put("TO", TokenType.TO);
        KEYWORDS.put("STEP", TokenType.STEP);
        KEYWORDS.put("NEXT", TokenType.NEXT);
        KEYWORDS.put("WHILE", TokenType.WHILE);
        KEYWORDS.put("WEND", TokenType.WEND);
        KEYWORDS.put("DO", TokenType.DO);
        KEYWORDS.put("LOOP", TokenType.LOOP);
        KEYWORDS.put("UNTIL", TokenType.UNTIL);
        KEYWORDS.put("GOTO", TokenType.GOTO);
        KEYWORDS.put("GOSUB", TokenType.GOSUB);
        KEYWORDS.put("RETURN", TokenType.RETURN);
        KEYWORDS.put("SUB", TokenType.SUB);
        KEYWORDS.put("CALL", TokenType.CALL);
        KEYWORDS.put("FUNCTION", TokenType.FUNCTION);
        KEYWORDS.put("HIGH", TokenType.HIGH);
        KEYWORDS.put("LOW", TokenType.LOW);
        KEYWORDS.put("TOGGLE", TokenType.TOGGLE);
        KEYWORDS.put("OUTPUT", TokenType.OUTPUT);
        KEYWORDS.put("INPUT", TokenType.INPUT);
        KEYWORDS.put("DELAY", TokenType.DELAY);
        KEYWORDS.put("DELAYUS", TokenType.DELAYUS);
        KEYWORDS.put("UARTINIT", TokenType.UARTINIT);
        KEYWORDS.put("UARTWRITE", TokenType.UARTWRITE);
        KEYWORDS.put("PRINT", TokenType.PRINT);
        KEYWORDS.put("POKE", TokenType.POKE);
        KEYWORDS.put("PEEK", TokenType.PEEK);
        KEYWORDS.put("PIN", TokenType.PIN);
        KEYWORDS.put("ADC", TokenType.ADC);
        KEYWORDS.put("AND", TokenType.AND);
        KEYWORDS.put("OR", TokenType.OR);
        KEYWORDS.put("XOR", TokenType.XOR);
        KEYWORDS.put("NOT", TokenType.NOT);
        KEYWORDS.put("MOD", TokenType.MOD);
        KEYWORDS.put("TRUE", TokenType.TRUE);
        KEYWORDS.put("FALSE", TokenType.FALSE);
    }

    private final String src;
    private final DiagnosticReporter reporter;
    private int pos = 0;
    private int line = 1;
    private int col = 1;
    private final List<Token> tokens = new ArrayList<>();

    public Lexer(String src, DiagnosticReporter reporter) {
        this.src = src;
        this.reporter = reporter;
    }

    public List<Token> tokenize() {
        while (!eof()) {
            char c = peek();
            if (c == '\n') {
                addNewline();
                advance();
            } else if (c == '\r') {
                advance();
            } else if (c == ' ' || c == '\t') {
                advance();
            } else if (c == '\'') {
                skipLineComment();
            } else if (isDigit(c) || (c == '&') || (c == '%')) {
                lexNumber();
            } else if (isLetter(c) || c == '_') {
                lexIdentOrKeyword();
            } else if (c == '"') {
                lexString();
            } else {
                lexOperator();
            }
        }
        // Ensure a trailing newline then EOF.
        if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).type != TokenType.NEWLINE) {
            tokens.add(new Token(TokenType.NEWLINE, "\\n", 0, line, col));
        }
        tokens.add(new Token(TokenType.EOF, "<eof>", 0, line, col));
        return tokens;
    }

    private void addNewline() {
        // Collapse consecutive blank lines into single NEWLINE tokens.
        if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).type == TokenType.NEWLINE) {
            return;
        }
        if (tokens.isEmpty()) {
            return;
        }
        tokens.add(new Token(TokenType.NEWLINE, "\\n", 0, line, col));
    }

    private void skipLineComment() {
        while (!eof() && peek() != '\n') {
            advance();
        }
    }

    private void lexNumber() {
        int startLine = line, startCol = col;
        StringBuilder sb = new StringBuilder();
        int value;
        if (peek() == '&') {
            advance(); // consume &
            char kind = Character.toUpperCase(peek());
            if (kind == 'H') {
                advance();
                while (!eof() && isHex(peek())) { sb.append(peek()); advance(); }
                value = parseRadix(sb.toString(), 16, startLine, startCol);
            } else if (kind == 'B') {
                advance();
                while (!eof() && (peek() == '0' || peek() == '1')) { sb.append(peek()); advance(); }
                value = parseRadix(sb.toString(), 2, startLine, startCol);
            } else {
                // &O octal
                advance();
                while (!eof() && peek() >= '0' && peek() <= '7') { sb.append(peek()); advance(); }
                value = parseRadix(sb.toString(), 8, startLine, startCol);
            }
        } else if (peek() == '%') {
            advance();
            while (!eof() && (peek() == '0' || peek() == '1')) { sb.append(peek()); advance(); }
            value = parseRadix(sb.toString(), 2, startLine, startCol);
        } else if (peek() == '0' && pos + 1 < src.length()
                && (src.charAt(pos + 1) == 'x' || src.charAt(pos + 1) == 'X')) {
            advance(); advance();
            while (!eof() && isHex(peek())) { sb.append(peek()); advance(); }
            value = parseRadix(sb.toString(), 16, startLine, startCol);
        } else {
            while (!eof() && isDigit(peek())) { sb.append(peek()); advance(); }
            value = parseRadix(sb.toString(), 10, startLine, startCol);
        }
        tokens.add(new Token(TokenType.NUMBER, sb.toString(), value, startLine, startCol));
    }

    private int parseRadix(String s, int radix, int l, int c) {
        if (s.isEmpty()) {
            reporter.error(l, c, "malformed numeric literal");
            return 0;
        }
        try {
            return Integer.parseInt(s, radix);
        } catch (NumberFormatException e) {
            reporter.error(l, c, "numeric literal out of range: " + s);
            return 0;
        }
    }

    private void lexIdentOrKeyword() {
        int startLine = line, startCol = col;
        StringBuilder sb = new StringBuilder();
        while (!eof() && (isLetter(peek()) || isDigit(peek()) || peek() == '_')) {
            sb.append(peek());
            advance();
        }
        String word = sb.toString();
        String upper = word.toUpperCase();
        if (upper.equals("REM")) {
            skipLineComment();
            return;
        }
        TokenType kw = KEYWORDS.get(upper);
        if (kw != null) {
            tokens.add(new Token(kw, upper, 0, startLine, startCol));
        } else {
            tokens.add(new Token(TokenType.IDENT, word, 0, startLine, startCol));
        }
    }

    private void lexString() {
        int startLine = line, startCol = col;
        advance(); // opening quote
        StringBuilder sb = new StringBuilder();
        while (!eof() && peek() != '"') {
            char ch = peek();
            if (ch == '\n') {
                reporter.error(startLine, startCol, "unterminated string literal");
                break;
            }
            if (ch == '\\') {
                advance();
                char esc = eof() ? '\\' : peek();
                switch (esc) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '0': sb.append('\0'); break;
                    case '\\': sb.append('\\'); break;
                    case '"': sb.append('"'); break;
                    default: sb.append(esc); break;
                }
                advance();
            } else {
                sb.append(ch);
                advance();
            }
        }
        if (!eof()) {
            advance(); // closing quote
        }
        tokens.add(new Token(TokenType.STRING, sb.toString(), 0, startLine, startCol));
    }

    private void lexOperator() {
        int startLine = line, startCol = col;
        char c = peek();
        switch (c) {
            case '=': advance(); add(TokenType.EQ, "=", startLine, startCol); return;
            case '+': advance(); add(TokenType.PLUS, "+", startLine, startCol); return;
            case '-': advance(); add(TokenType.MINUS, "-", startLine, startCol); return;
            case '*': advance(); add(TokenType.STAR, "*", startLine, startCol); return;
            case '/': advance(); add(TokenType.SLASH, "/", startLine, startCol); return;
            case '(': advance(); add(TokenType.LPAREN, "(", startLine, startCol); return;
            case ')': advance(); add(TokenType.RPAREN, ")", startLine, startCol); return;
            case ',': advance(); add(TokenType.COMMA, ",", startLine, startCol); return;
            case ':': advance(); add(TokenType.COLON, ":", startLine, startCol); return;
            case ';': advance(); add(TokenType.SEMICOLON, ";", startLine, startCol); return;
            case '<':
                advance();
                if (peek() == '>') { advance(); add(TokenType.NE, "<>", startLine, startCol); }
                else if (peek() == '=') { advance(); add(TokenType.LE, "<=", startLine, startCol); }
                else if (peek() == '<') { advance(); add(TokenType.SHL, "<<", startLine, startCol); }
                else add(TokenType.LT, "<", startLine, startCol);
                return;
            case '>':
                advance();
                if (peek() == '=') { advance(); add(TokenType.GE, ">=", startLine, startCol); }
                else if (peek() == '>') { advance(); add(TokenType.SHR, ">>", startLine, startCol); }
                else add(TokenType.GT, ">", startLine, startCol);
                return;
            default:
                reporter.error(startLine, startCol, "unexpected character '" + c + "'");
                advance();
        }
    }

    private void add(TokenType t, String text, int l, int c) {
        tokens.add(new Token(t, text, 0, l, c));
    }

    // ------------------------------------------------------------------
    // Low-level scanning helpers
    // ------------------------------------------------------------------

    private boolean eof() { return pos >= src.length(); }
    private char peek() { return eof() ? '\0' : src.charAt(pos); }

    private void advance() {
        if (peek() == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        pos++;
    }

    private static boolean isDigit(char c)  { return c >= '0' && c <= '9'; }
    private static boolean isLetter(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }
    private static boolean isHex(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
