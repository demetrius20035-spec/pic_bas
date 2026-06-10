package org.basic2asm;

import org.basic2asm.ast.Program;
import org.basic2asm.chip.ChipDefinition;
import org.basic2asm.codegen.CodeGenerator;
import org.basic2asm.config.Config;
import org.basic2asm.diag.DiagnosticReporter;
import org.basic2asm.lexer.Lexer;
import org.basic2asm.lexer.Token;
import org.basic2asm.parser.Parser;
import org.basic2asm.sema.SemanticAnalyzer;

import java.util.List;

/**
 * Programmatic entry point for embedding the transpiler. Runs the full
 * lex → parse → analyze → generate pipeline and returns the assembly (or null
 * if any phase reported an error). This is the stable API for developers who
 * want to use basic2asm as a library; the CLI {@link Main} is a thin wrapper
 * around it.
 *
 * <pre>{@code
 *   Config cfg = new Config();
 *   cfg.clockHz = 4_000_000L;
 *   DiagnosticReporter reporter = new DiagnosticReporter("prog.bas", source);
 *   ChipDefinition chip = ChipRegistry.get("PIC16F84A");
 *   Transpiler.Output out = Transpiler.compile(source, chip, cfg, reporter);
 *   if (out != null) System.out.println(out.asm);
 * }</pre>
 */
public final class Transpiler {

    private Transpiler() { }

    /** Successful compilation output. */
    public static final class Output {
        public final String asm;
        public final List<String> mapLines;
        public final int estimatedWords;
        public Output(String asm, List<String> mapLines, int estimatedWords) {
            this.asm = asm; this.mapLines = mapLines; this.estimatedWords = estimatedWords;
        }
    }

    /**
     * Compile BASIC source for the given chip. Errors and warnings are appended
     * to {@code reporter}. Returns {@code null} if compilation failed (check
     * {@code reporter.hasErrors()}).
     */
    public static Output compile(String source, ChipDefinition chip, Config config,
                                 DiagnosticReporter reporter) {
        List<Token> tokens = new Lexer(source, reporter).tokenize();
        Program program = new Parser(tokens, reporter).parse();
        if (reporter.hasErrors()) {
            return null;
        }
        new SemanticAnalyzer(program, reporter).analyze();
        if (reporter.hasErrors()) {
            return null;
        }
        CodeGenerator.Result result = new CodeGenerator(program, chip, config, reporter).generate();
        if (result == null || reporter.hasErrors()) {
            return null;
        }
        return new Output(result.asm, result.mapLines, result.estimatedWords);
    }
}
