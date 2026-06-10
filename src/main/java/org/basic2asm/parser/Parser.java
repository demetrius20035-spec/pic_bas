package org.basic2asm.parser;

import org.basic2asm.ast.Expr;
import org.basic2asm.ast.Program;
import org.basic2asm.ast.Stmt;
import org.basic2asm.ast.VarType;
import org.basic2asm.diag.CompileException;
import org.basic2asm.diag.DiagnosticReporter;
import org.basic2asm.lexer.Token;
import org.basic2asm.lexer.TokenType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Recursive-descent parser for the basic2asm BASIC dialect. Produces a {@link
 * Program}. Errors are reported through the {@link DiagnosticReporter}; the
 * parser uses panic-mode recovery at statement boundaries so that more than one
 * syntax error can be reported per run.
 */
public final class Parser {

    private final List<Token> tokens;
    private final DiagnosticReporter reporter;
    private int idx = 0;

    private static final Set<TokenType> BLOCK_END = EnumSet.of(
            TokenType.ELSE, TokenType.ELSEIF, TokenType.ENDIF, TokenType.END,
            TokenType.NEXT, TokenType.WEND, TokenType.LOOP, TokenType.UNTIL, TokenType.EOF);

    public Parser(List<Token> tokens, DiagnosticReporter reporter) {
        this.tokens = tokens;
        this.reporter = reporter;
    }

    public Program parse() {
        Program program = new Program();
        skipSeparators();
        while (!check(TokenType.EOF)) {
            try {
                if (check(TokenType.SUB) || check(TokenType.FUNCTION)) {
                    program.subs.add(parseSubDecl());
                } else {
                    program.main.add(parseStatement());
                }
            } catch (CompileException e) {
                reporter.error(e.getLine(), e.getColumn(), e.getMessage());
                synchronize();
            }
            skipSeparators();
        }
        return program;
    }

    // ------------------------------------------------------------------
    // Statements
    // ------------------------------------------------------------------

    private Stmt parseStatement() {
        Token t = peek();
        switch (t.type) {
            case DIM:       return parseDim();
            case CONST:     return parseConst();
            case LET:       advance(); return parseAssignFromName();
            case IF:        return parseIf();
            case FOR:       return parseFor();
            case WHILE:     return parseWhile();
            case DO:        return parseDoLoop();
            case GOTO:      return parseGoto();
            case GOSUB:     return parseGosub();
            case RETURN:    return parseReturn();
            case CALL:      return parseCall();
            case HIGH: case LOW: case TOGGLE: return parsePinOp();
            case OUTPUT: case INPUT:          return parseDirection();
            case DELAY: case DELAYUS:         return parseDelay();
            case UARTINIT:  advance(); return new Stmt.UartInit(t.line, t.column);
            case UARTWRITE: return parseUartWrite();
            case PRINT:     return parsePrint();
            case POKE:      return parsePoke();
            case END:       return parseEnd();
            case IDENT:
                if (peekIs(1, TokenType.COLON)) {
                    return parseLabel();
                }
                return parseAssignFromName();
            default:
                throw error(t, "expected a statement, found '" + t.text + "'");
        }
    }

    private Stmt parseDim() {
        Token kw = expect(TokenType.DIM, "expected DIM");
        Token name = expect(TokenType.IDENT, "expected variable name after DIM");
        VarType type = VarType.BYTE;
        if (match(TokenType.AS)) {
            type = parseType();
        }
        Expr init = null;
        if (match(TokenType.EQ)) {
            init = parseExpr();
        }
        return new Stmt.Dim(name.text, type, init, kw.line, kw.column);
    }

    private VarType parseType() {
        Token t = peek();
        switch (t.type) {
            case BYTE: advance(); return VarType.BYTE;
            case WORD: advance(); return VarType.WORD;
            case BIT:  advance(); return VarType.BIT;
            default: throw error(t, "expected a type (BYTE, WORD or BIT)");
        }
    }

    private Stmt parseConst() {
        Token kw = expect(TokenType.CONST, "expected CONST");
        Token name = expect(TokenType.IDENT, "expected constant name");
        expect(TokenType.EQ, "expected '=' in CONST declaration");
        Expr value = parseExpr();
        return new Stmt.Const(name.text, value, kw.line, kw.column);
    }

    private Stmt parseAssignFromName() {
        Token name = expect(TokenType.IDENT, "expected assignment target");
        expect(TokenType.EQ, "expected '=' in assignment");
        Expr value = parseExpr();
        Stmt.Target target = new Stmt.Target(name.text, name.line, name.column);
        return new Stmt.Assign(target, value, name.line, name.column);
    }

    private Stmt parseLabel() {
        Token name = expect(TokenType.IDENT, "expected label name");
        expect(TokenType.COLON, "expected ':' after label");
        return new Stmt.Label(name.text, name.line, name.column);
    }

    private Stmt parseIf() {
        Token kw = expect(TokenType.IF, "expected IF");
        Expr cond = parseExpr();
        expect(TokenType.THEN, "expected THEN");
        // Single-line form: IF cond THEN stmt [ELSE stmt]
        if (!check(TokenType.NEWLINE) && !check(TokenType.COLON)) {
            List<Stmt> thenBody = new ArrayList<>();
            thenBody.add(parseStatement());
            List<Stmt> elseBody = null;
            if (match(TokenType.ELSE)) {
                elseBody = new ArrayList<>();
                elseBody.add(parseStatement());
            }
            return new Stmt.If(cond, thenBody, new ArrayList<Stmt.ElseIf>(), elseBody, kw.line, kw.column);
        }
        // Block form.
        List<Stmt> thenBody = parseBlock();
        List<Stmt.ElseIf> elseIfs = new ArrayList<>();
        while (check(TokenType.ELSEIF)) {
            advance();
            Expr ec = parseExpr();
            expect(TokenType.THEN, "expected THEN after ELSEIF condition");
            List<Stmt> body = parseBlock();
            elseIfs.add(new Stmt.ElseIf(ec, body));
        }
        List<Stmt> elseBody = null;
        if (match(TokenType.ELSE)) {
            elseBody = parseBlock();
        }
        consumeEndKeyword(TokenType.ENDIF, TokenType.IF, "ENDIF");
        return new Stmt.If(cond, thenBody, elseIfs, elseBody, kw.line, kw.column);
    }

    private Stmt parseFor() {
        Token kw = expect(TokenType.FOR, "expected FOR");
        Token var = expect(TokenType.IDENT, "expected loop variable");
        expect(TokenType.EQ, "expected '=' in FOR");
        Expr from = parseExpr();
        expect(TokenType.TO, "expected TO in FOR");
        Expr to = parseExpr();
        Expr step = null;
        if (match(TokenType.STEP)) {
            step = parseExpr();
        }
        List<Stmt> body = parseBlock();
        expect(TokenType.NEXT, "expected NEXT to close FOR");
        if (check(TokenType.IDENT)) {
            advance(); // optional loop-variable name after NEXT
        }
        return new Stmt.For(var.text, from, to, step, body, kw.line, kw.column);
    }

    private Stmt parseWhile() {
        Token kw = expect(TokenType.WHILE, "expected WHILE");
        Expr cond = parseExpr();
        List<Stmt> body = parseBlock();
        expect(TokenType.WEND, "expected WEND to close WHILE");
        return new Stmt.While(cond, body, kw.line, kw.column);
    }

    private Stmt parseDoLoop() {
        Token kw = expect(TokenType.DO, "expected DO");
        if (match(TokenType.WHILE)) {
            Expr cond = parseExpr();
            List<Stmt> body = parseBlock();
            expect(TokenType.LOOP, "expected LOOP");
            return new Stmt.DoLoop(Stmt.DoLoop.Kind.PRE_WHILE, cond, body, kw.line, kw.column);
        }
        if (match(TokenType.UNTIL)) {
            Expr cond = parseExpr();
            List<Stmt> body = parseBlock();
            expect(TokenType.LOOP, "expected LOOP");
            return new Stmt.DoLoop(Stmt.DoLoop.Kind.PRE_UNTIL, cond, body, kw.line, kw.column);
        }
        List<Stmt> body = parseBlock();
        expect(TokenType.LOOP, "expected LOOP");
        if (match(TokenType.WHILE)) {
            return new Stmt.DoLoop(Stmt.DoLoop.Kind.POST_WHILE, parseExpr(), body, kw.line, kw.column);
        }
        if (match(TokenType.UNTIL)) {
            return new Stmt.DoLoop(Stmt.DoLoop.Kind.POST_UNTIL, parseExpr(), body, kw.line, kw.column);
        }
        return new Stmt.DoLoop(Stmt.DoLoop.Kind.INFINITE, null, body, kw.line, kw.column);
    }

    private Stmt parseGoto() {
        Token kw = expect(TokenType.GOTO, "expected GOTO");
        Token label = expect(TokenType.IDENT, "expected label after GOTO");
        return new Stmt.Goto(label.text, kw.line, kw.column);
    }

    private Stmt parseGosub() {
        Token kw = expect(TokenType.GOSUB, "expected GOSUB");
        Token label = expect(TokenType.IDENT, "expected label after GOSUB");
        return new Stmt.Gosub(label.text, kw.line, kw.column);
    }

    private Stmt parseReturn() {
        Token kw = expect(TokenType.RETURN, "expected RETURN");
        Expr value = null;
        if (!check(TokenType.NEWLINE) && !check(TokenType.COLON) && !check(TokenType.EOF)) {
            value = parseExpr();
        }
        return new Stmt.Return(value, kw.line, kw.column);
    }

    private Stmt parseCall() {
        Token kw = expect(TokenType.CALL, "expected CALL");
        Token name = expect(TokenType.IDENT, "expected subroutine name");
        List<Expr> args = new ArrayList<>();
        if (match(TokenType.LPAREN)) {
            args = parseArgList();
            expect(TokenType.RPAREN, "expected ')' after arguments");
        }
        return new Stmt.CallStmt(name.text, args, kw.line, kw.column);
    }

    private Stmt parsePinOp() {
        Token kw = advance();
        Stmt.PinOp.Kind kind = kw.type == TokenType.HIGH ? Stmt.PinOp.Kind.HIGH
                : kw.type == TokenType.LOW ? Stmt.PinOp.Kind.LOW : Stmt.PinOp.Kind.TOGGLE;
        Token pin = expect(TokenType.IDENT, "expected pin name (e.g. RB0)");
        return new Stmt.PinOp(kind, pin.text, kw.line, kw.column);
    }

    private Stmt parseDirection() {
        Token kw = advance();
        boolean output = kw.type == TokenType.OUTPUT;
        Token target = expect(TokenType.IDENT, "expected pin or port name");
        return new Stmt.Direction(output, target.text, kw.line, kw.column);
    }

    private Stmt parseDelay() {
        Token kw = advance();
        boolean us = kw.type == TokenType.DELAYUS;
        Expr amount = parseExpr();
        return new Stmt.Delay(us, amount, kw.line, kw.column);
    }

    private Stmt parseUartWrite() {
        Token kw = expect(TokenType.UARTWRITE, "expected UARTWRITE");
        Expr value = parseExpr();
        return new Stmt.UartWrite(value, kw.line, kw.column);
    }

    private Stmt parsePrint() {
        Token kw = expect(TokenType.PRINT, "expected PRINT");
        List<Stmt.Print.Item> items = new ArrayList<>();
        boolean newline = true;
        while (!check(TokenType.NEWLINE) && !check(TokenType.COLON) && !check(TokenType.EOF)) {
            if (check(TokenType.STRING)) {
                Token s = advance();
                items.add(new Stmt.Print.Item(s.text, null));
            } else {
                items.add(new Stmt.Print.Item(null, parseExpr()));
            }
            if (check(TokenType.COMMA)) {
                advance();
            } else if (check(TokenType.SEMICOLON)) {
                advance();
                if (check(TokenType.NEWLINE) || check(TokenType.COLON) || check(TokenType.EOF)) {
                    newline = false;
                }
            } else {
                break;
            }
        }
        return new Stmt.Print(items, newline, kw.line, kw.column);
    }

    private Stmt parsePoke() {
        Token kw = expect(TokenType.POKE, "expected POKE");
        Token reg = expect(TokenType.IDENT, "expected register name after POKE");
        expect(TokenType.COMMA, "expected ',' after register name");
        Expr value = parseExpr();
        return new Stmt.Poke(reg.text, value, kw.line, kw.column);
    }

    private Stmt parseEnd() {
        Token kw = expect(TokenType.END, "expected END");
        // A stray "END IF/SUB/FUNCTION" here means an unbalanced block.
        if (check(TokenType.IF) || check(TokenType.SUB) || check(TokenType.FUNCTION)) {
            throw error(kw, "unexpected '" + peek().text + "' without a matching opener");
        }
        return new Stmt.End(kw.line, kw.column);
    }

    private Stmt.SubDecl parseSubDecl() {
        Token kw = advance(); // SUB or FUNCTION
        boolean isFunction = kw.type == TokenType.FUNCTION;
        Token name = expect(TokenType.IDENT, "expected " + (isFunction ? "function" : "subroutine") + " name");
        List<String> params = new ArrayList<>();
        if (match(TokenType.LPAREN)) {
            if (!check(TokenType.RPAREN)) {
                do {
                    Token p = expect(TokenType.IDENT, "expected parameter name");
                    params.add(p.text);
                } while (match(TokenType.COMMA));
            }
            expect(TokenType.RPAREN, "expected ')' after parameters");
        }
        List<Stmt> body = parseBlock();
        consumeEndKeyword(null, isFunction ? TokenType.FUNCTION : TokenType.SUB,
                "END " + (isFunction ? "FUNCTION" : "SUB"));
        return new Stmt.SubDecl(isFunction, name.text, params, body, kw.line, kw.column);
    }

    // ------------------------------------------------------------------
    // Blocks
    // ------------------------------------------------------------------

    /** Parse statements up to (but not consuming) the next block-terminating keyword. */
    private List<Stmt> parseBlock() {
        List<Stmt> body = new ArrayList<>();
        consumeSeparators();
        while (!BLOCK_END.contains(peek().type)) {
            body.add(parseStatement());
            consumeSeparators();
        }
        return body;
    }

    /**
     * Consume a block closer that may be written as one word (e.g. ENDIF) or two
     * words (e.g. END IF). {@code single} may be null when only the two-word form
     * applies (END SUB / END FUNCTION).
     */
    private void consumeEndKeyword(TokenType single, TokenType secondWord, String label) {
        if (single != null && match(single)) {
            return;
        }
        if (match(TokenType.END)) {
            expect(secondWord, "expected '" + label + "'");
            return;
        }
        throw error(peek(), "expected '" + label + "'");
    }

    // ------------------------------------------------------------------
    // Expressions (precedence climbing)
    // ------------------------------------------------------------------

    private Expr parseExpr() { return parseOr(); }

    private Expr parseOr() {
        Expr left = parseAnd();
        while (check(TokenType.OR) || check(TokenType.XOR)) {
            Token op = advance();
            Expr right = parseAnd();
            Expr.Binary.Op bop = op.type == TokenType.OR ? Expr.Binary.Op.OR : Expr.Binary.Op.XOR;
            left = new Expr.Binary(bop, left, right, op.line, op.column);
        }
        return left;
    }

    private Expr parseAnd() {
        Expr left = parseEquality();
        while (check(TokenType.AND)) {
            Token op = advance();
            Expr right = parseEquality();
            left = new Expr.Binary(Expr.Binary.Op.AND, left, right, op.line, op.column);
        }
        return left;
    }

    private Expr parseEquality() {
        Expr left = parseRelational();
        while (check(TokenType.EQ) || check(TokenType.NE)) {
            Token op = advance();
            Expr right = parseRelational();
            Expr.Binary.Op bop = op.type == TokenType.EQ ? Expr.Binary.Op.EQ : Expr.Binary.Op.NE;
            left = new Expr.Binary(bop, left, right, op.line, op.column);
        }
        return left;
    }

    private Expr parseRelational() {
        Expr left = parseShift();
        while (check(TokenType.LT) || check(TokenType.LE) || check(TokenType.GT) || check(TokenType.GE)) {
            Token op = advance();
            Expr right = parseShift();
            Expr.Binary.Op bop;
            switch (op.type) {
                case LT: bop = Expr.Binary.Op.LT; break;
                case LE: bop = Expr.Binary.Op.LE; break;
                case GT: bop = Expr.Binary.Op.GT; break;
                default: bop = Expr.Binary.Op.GE; break;
            }
            left = new Expr.Binary(bop, left, right, op.line, op.column);
        }
        return left;
    }

    private Expr parseShift() {
        Expr left = parseAdditive();
        while (check(TokenType.SHL) || check(TokenType.SHR)) {
            Token op = advance();
            Expr right = parseAdditive();
            Expr.Binary.Op bop = op.type == TokenType.SHL ? Expr.Binary.Op.SHL : Expr.Binary.Op.SHR;
            left = new Expr.Binary(bop, left, right, op.line, op.column);
        }
        return left;
    }

    private Expr parseAdditive() {
        Expr left = parseTerm();
        while (check(TokenType.PLUS) || check(TokenType.MINUS)) {
            Token op = advance();
            Expr right = parseTerm();
            Expr.Binary.Op bop = op.type == TokenType.PLUS ? Expr.Binary.Op.ADD : Expr.Binary.Op.SUB;
            left = new Expr.Binary(bop, left, right, op.line, op.column);
        }
        return left;
    }

    private Expr parseTerm() {
        Expr left = parseUnary();
        while (check(TokenType.STAR) || check(TokenType.SLASH) || check(TokenType.MOD)) {
            Token op = advance();
            Expr right = parseUnary();
            Expr.Binary.Op bop;
            switch (op.type) {
                case STAR:  bop = Expr.Binary.Op.MUL; break;
                case SLASH: bop = Expr.Binary.Op.DIV; break;
                default:    bop = Expr.Binary.Op.MOD; break;
            }
            left = new Expr.Binary(bop, left, right, op.line, op.column);
        }
        return left;
    }

    private Expr parseUnary() {
        if (check(TokenType.MINUS)) {
            Token op = advance();
            return new Expr.Unary(Expr.Unary.Op.NEG, parseUnary(), op.line, op.column);
        }
        if (check(TokenType.NOT)) {
            Token op = advance();
            return new Expr.Unary(Expr.Unary.Op.NOT, parseUnary(), op.line, op.column);
        }
        return parsePrimary();
    }

    private Expr parsePrimary() {
        Token t = peek();
        switch (t.type) {
            case NUMBER: advance(); return new Expr.Num(t.intValue, t.line, t.column);
            case TRUE:   advance(); return new Expr.Num(1, t.line, t.column);
            case FALSE:  advance(); return new Expr.Num(0, t.line, t.column);
            case LPAREN: {
                advance();
                Expr e = parseExpr();
                expect(TokenType.RPAREN, "expected ')'");
                return e;
            }
            case PIN: {
                advance();
                expect(TokenType.LPAREN, "expected '(' after PIN");
                Token name = expect(TokenType.IDENT, "expected pin name in PIN(...)");
                expect(TokenType.RPAREN, "expected ')'");
                return new Expr.Name(name.text, name.line, name.column);
            }
            case PEEK: {
                advance();
                expect(TokenType.LPAREN, "expected '(' after PEEK");
                Expr addr = parseExpr();
                expect(TokenType.RPAREN, "expected ')'");
                return new Expr.Peek(addr, t.line, t.column);
            }
            case ADC: {
                advance();
                expect(TokenType.LPAREN, "expected '(' after ADC");
                Expr ch = parseExpr();
                expect(TokenType.RPAREN, "expected ')'");
                return new Expr.AdcRead(ch, t.line, t.column);
            }
            case IDENT: {
                advance();
                if (match(TokenType.LPAREN)) {
                    List<Expr> args = parseArgList();
                    expect(TokenType.RPAREN, "expected ')' after arguments");
                    return new Expr.Call(t.text, args, t.line, t.column);
                }
                return new Expr.Name(t.text, t.line, t.column);
            }
            default:
                throw error(t, "expected an expression, found '" + t.text + "'");
        }
    }

    private List<Expr> parseArgList() {
        List<Expr> args = new ArrayList<>();
        if (check(TokenType.RPAREN)) {
            return args;
        }
        do {
            args.add(parseExpr());
        } while (match(TokenType.COMMA));
        return args;
    }

    // ------------------------------------------------------------------
    // Token plumbing
    // ------------------------------------------------------------------

    private Token peek() { return tokens.get(idx); }

    private boolean peekIs(int ahead, TokenType type) {
        int i = idx + ahead;
        return i < tokens.size() && tokens.get(i).type == type;
    }

    private Token advance() {
        Token t = tokens.get(idx);
        if (t.type != TokenType.EOF) idx++;
        return t;
    }

    private boolean check(TokenType type) { return peek().type == type; }

    private boolean match(TokenType type) {
        if (check(type)) { advance(); return true; }
        return false;
    }

    private Token expect(TokenType type, String message) {
        if (check(type)) return advance();
        throw error(peek(), message + " (found '" + peek().text + "')");
    }

    private void consumeSeparators() {
        while (check(TokenType.NEWLINE) || check(TokenType.COLON)) {
            advance();
        }
    }

    private void skipSeparators() { consumeSeparators(); }

    private CompileException error(Token t, String message) {
        return new CompileException(message, t.line, t.column);
    }

    /** Panic-mode recovery: skip to the next statement separator. */
    private void synchronize() {
        while (!check(TokenType.EOF) && !check(TokenType.NEWLINE) && !check(TokenType.COLON)) {
            advance();
        }
    }
}
