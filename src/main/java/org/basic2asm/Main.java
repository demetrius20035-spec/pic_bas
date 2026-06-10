package org.basic2asm;

import org.basic2asm.chip.ChipDefinition;
import org.basic2asm.chip.ChipRegistry;
import org.basic2asm.cli.ArgParser;
import org.basic2asm.config.Config;
import org.basic2asm.diag.DiagnosticReporter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Command-line entry point for the basic2asm transpiler.
 *
 * <pre>
 *   java -jar basic2asm.jar --chip PIC16F84A --input blink.bas --output blink.asm \
 *        --clock 4MHz --optimize size
 * </pre>
 */
public final class Main {

    public static final String VERSION = "1.0.0";

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        ArgParser ap = ArgParser.parse(args);
        if (ap.error != null) {
            System.err.println("basic2asm: " + ap.error);
            System.err.println("Try 'basic2asm --help' for usage.");
            return 2;
        }
        switch (ap.action) {
            case HELP:       printHelp();    return 0;
            case VERSION:    printVersion(); return 0;
            case LIST_CHIPS: printChips();   return 0;
            default:         break;
        }
        return transpile(ap);
    }

    private static int transpile(ArgParser ap) {
        Config cfg = ap.config;

        if (cfg.chip == null) {
            System.err.println("basic2asm: no target chip specified (use --chip NAME or --list-chips)");
            return 2;
        }
        ChipDefinition chip = ChipRegistry.get(cfg.chip);
        if (chip == null) {
            System.err.println("basic2asm: unsupported chip '" + cfg.chip
                    + "'. Use --list-chips to see supported parts.");
            return 2;
        }
        if (cfg.inputFile == null) {
            System.err.println("basic2asm: no input file (use --input FILE)");
            return 2;
        }

        String source;
        try {
            source = new String(Files.readAllBytes(Paths.get(cfg.inputFile)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("basic2asm: cannot read input '" + cfg.inputFile + "': " + e.getMessage());
            return 2;
        }

        if (cfg.outputFile == null) {
            cfg.outputFile = stripExt(cfg.inputFile) + ".asm";
        }
        if (ap.wantList && cfg.listFile == null) {
            cfg.listFile = stripExt(cfg.outputFile) + ".lst";
        }
        if (ap.wantMap && cfg.mapFile == null) {
            cfg.mapFile = stripExt(cfg.outputFile) + ".map";
        }

        DiagnosticReporter reporter = new DiagnosticReporter(cfg.inputFile, source);

        // Run the full lex -> parse -> analyze -> generate pipeline.
        Transpiler.Output result = Transpiler.compile(source, chip, cfg, reporter);
        reporter.printAll(System.err);
        if (result == null || reporter.hasErrors()) {
            return 1;
        }

        // ---- Write outputs ----
        try {
            Files.write(Paths.get(cfg.outputFile), result.asm.getBytes(StandardCharsets.UTF_8));
            System.out.println("Wrote " + cfg.outputFile + " (" + result.estimatedWords
                    + " instruction words est., " + chip.getProgramWords() + " available)");

            if (cfg.mapFile != null) {
                writeMap(cfg, chip, result);
                System.out.println("Wrote " + cfg.mapFile);
            }
            if (cfg.listFile != null) {
                writeListing(cfg, result);
                System.out.println("Wrote " + cfg.listFile);
            }
        } catch (IOException e) {
            System.err.println("basic2asm: cannot write output: " + e.getMessage());
            return 2;
        }

        if (reporter.warningCount() > 0) {
            System.out.println(reporter.warningCount() + " warning(s).");
        }
        return 0;
    }

    private static void writeMap(Config cfg, ChipDefinition chip, Transpiler.Output result) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("; basic2asm memory map for ").append(chip.getName()).append('\n');
        sb.append("; ----------------------------------------------\n");
        sb.append("; Flash : ").append(chip.getProgramWords()).append(" words (est. used ")
          .append(result.estimatedWords).append(")\n");
        sb.append("; RAM   : ").append(chip.getGprBytes()).append(" bytes, GPR start 0x")
          .append(Integer.toHexString(chip.getGprStart()).toUpperCase()).append('\n');
        sb.append("; ----------------------------------------------\n");
        sb.append("; RAM allocation (addr  name  size):\n");
        for (String l : result.mapLines) {
            sb.append("; ").append(l).append('\n');
        }
        Files.write(Paths.get(cfg.mapFile), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeListing(Config cfg, Transpiler.Output result) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("; basic2asm source listing (not an MPASM .lst; assemble with MPASM for the real listing)\n");
        int line = 1;
        for (String l : result.asm.split("\n", -1)) {
            sb.append(String.format("%5d  %s%n", line++, l));
        }
        Files.write(Paths.get(cfg.listFile), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String stripExt(String path) {
        int dot = path.lastIndexOf('.');
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return dot > slash ? path.substring(0, dot) : path;
    }

    private static void printVersion() {
        System.out.println("basic2asm " + VERSION);
    }

    private static void printChips() {
        System.out.println("Supported chips (" + ChipRegistry.supportedNames().size() + "):");
        for (ChipDefinition d : ChipRegistry.all()) {
            System.out.printf("  %-12s %-9s %5dw flash  %3dB RAM  %s%n",
                    d.getName(), d.getCore(), d.getProgramWords(), d.getGprBytes(), d.getPeripherals());
        }
    }

    private static void printHelp() {
        System.out.println(
            "basic2asm " + VERSION + " - BASIC to Microchip PIC12/PIC16 MPASM transpiler\n" +
            "\n" +
            "USAGE:\n" +
            "  java -jar basic2asm.jar --chip <NAME> --input <FILE.bas> [options]\n" +
            "\n" +
            "OPTIONS:\n" +
            "  --chip <NAME>          target microcontroller (e.g. PIC16F84A)\n" +
            "  -i, --input <FILE>     BASIC source file (.bas)\n" +
            "  -o, --output <FILE>    output assembly file (default: <input>.asm)\n" +
            "  --clock <FREQ>         oscillator frequency, e.g. 4MHz, 20MHz, 32768 (default 4MHz)\n" +
            "  --osc <MODE>           oscillator mode: XT, HS, LP, INTRC, EXTRC (default: auto)\n" +
            "  --optimize <size|speed> optimisation preference (default size)\n" +
            "  --comments / --no-comments  emit debug comments in the asm (default on)\n" +
            "  --uart-mode <software|hardware>  UART implementation (default software)\n" +
            "  --uart-baud <N>        UART baud rate (default 9600)\n" +
            "  --uart-tx <PIN>        software-UART TX pin (default RB2)\n" +
            "  --uart-rx <PIN>        software-UART RX pin (default RB1)\n" +
            "  --adc-bits <N>         ADC result width to use, 8 or 10 (default 8)\n" +
            "  --config-word <EXPR>   raw __CONFIG expression override\n" +
            "  --config <FILE.json>   load options from a JSON config file\n" +
            "  --list [FILE]          also write a source listing\n" +
            "  --map [FILE]           also write a memory map\n" +
            "  --list-chips           print all supported chips and exit\n" +
            "  --version              print version and exit\n" +
            "  -h, --help             show this help\n" +
            "\n" +
            "EXAMPLE:\n" +
            "  java -jar basic2asm.jar --chip PIC16F84A --input blink.bas --output blink.asm \\\n" +
            "       --clock 4MHz --optimize size --map\n");
    }
}
