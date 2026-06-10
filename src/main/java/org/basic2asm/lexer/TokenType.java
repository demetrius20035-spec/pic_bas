package org.basic2asm.lexer;

/**
 * Lexical token categories for the BASIC dialect understood by basic2asm.
 */
public enum TokenType {
    // Literals & identifiers
    NUMBER, STRING, IDENT,

    // Keywords
    DIM, AS, CONST, LET, BYTE, WORD, BIT,
    IF, THEN, ELSE, ELSEIF, ENDIF, END,
    FOR, TO, STEP, NEXT,
    WHILE, WEND,
    DO, LOOP, UNTIL,
    GOTO, GOSUB, RETURN,
    SUB, CALL, FUNCTION,
    HIGH, LOW, TOGGLE, OUTPUT, INPUT,
    DELAY, DELAYUS,
    UARTINIT, UARTWRITE, PRINT,
    POKE, PEEK, PIN, ADC,
    AND, OR, XOR, NOT, MOD,
    TRUE, FALSE,

    // Punctuation / operators
    EQ,          // =
    NE,          // <>
    LT, LE, GT, GE,
    PLUS, MINUS, STAR, SLASH,
    SHL, SHR,    // << >>
    LPAREN, RPAREN, COMMA, COLON, SEMICOLON,

    // Structure
    NEWLINE, EOF
}
