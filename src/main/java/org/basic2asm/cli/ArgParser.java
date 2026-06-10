package org.basic2asm.cli;

import org.basic2asm.config.Config;
import org.basic2asm.config.ConfigLoader;

import java.io.IOException;

/**
 * Parses command-line arguments into a {@link Config}. A {@code --config <file>}
 * option is processed first so that explicit flags always override file values.
 */
public final class ArgParser {

    /** Action requested on the command line (overrides normal transpilation). */
    public enum Action { TRANSPILE, HELP, VERSION, LIST_CHIPS }

    public Action action = Action.TRANSPILE;
    public final Config config = new Config();
    public boolean wantList = false;
    public boolean wantMap = false;
    public String error = null;

    public static ArgParser parse(String[] args) {
        ArgParser r = new ArgParser();
        try {
            r.doParse(args);
        } catch (IOException e) {
            r.error = "cannot read config file: " + e.getMessage();
        } catch (RuntimeException e) {
            r.error = e.getMessage();
        }
        return r;
    }

    private void doParse(String[] args) throws IOException {
        // First pass: handle --config so file values become the baseline.
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--config")) {
                ConfigLoader.applyJsonFile(requireValue(args, ++i, "--config"), config);
            }
        }
        // Second pass: explicit flags (override config-file values).
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h": case "--help":     action = Action.HELP; return;
                case "--version":             action = Action.VERSION; return;
                case "--list-chips":          action = Action.LIST_CHIPS; return;
                case "--config":              i++; break; // already handled
                case "--chip":                config.chip = requireValue(args, ++i, a); break;
                case "-i": case "--input":    config.inputFile = requireValue(args, ++i, a); break;
                case "-o": case "--output":   config.outputFile = requireValue(args, ++i, a); break;
                case "--clock":               config.clockHz = Config.parseClock(requireValue(args, ++i, a)); break;
                case "--osc":                 config.oscMode = requireValue(args, ++i, a).toUpperCase(); break;
                case "--optimize": {
                    String v = requireValue(args, ++i, a);
                    config.optimize = v.equalsIgnoreCase("speed") ? Config.Optimize.SPEED : Config.Optimize.SIZE;
                    break;
                }
                case "--comments":            config.comments = true; break;
                case "--no-comments":         config.comments = false; break;
                case "--uart-mode": {
                    String v = requireValue(args, ++i, a);
                    config.uartMode = v.equalsIgnoreCase("hardware")
                            ? Config.UartMode.HARDWARE : Config.UartMode.SOFTWARE;
                    break;
                }
                case "--uart-baud":           config.uartBaud = Integer.parseInt(requireValue(args, ++i, a)); break;
                case "--uart-tx":             config.uartTxPin = requireValue(args, ++i, a); break;
                case "--uart-rx":             config.uartRxPin = requireValue(args, ++i, a); break;
                case "--adc-bits":            config.adcResolution = Integer.parseInt(requireValue(args, ++i, a)); break;
                case "--config-word":         config.rawConfig = requireValue(args, ++i, a); break;
                case "--list":
                    wantList = true;
                    if (hasValue(args, i)) config.listFile = args[++i];
                    break;
                case "--map":
                    wantMap = true;
                    if (hasValue(args, i)) config.mapFile = args[++i];
                    break;
                default:
                    if (a.startsWith("-")) {
                        throw new IllegalArgumentException("unknown option: " + a);
                    }
                    // A bare argument is treated as the input file.
                    if (config.inputFile == null) {
                        config.inputFile = a;
                    } else {
                        throw new IllegalArgumentException("unexpected argument: " + a);
                    }
            }
        }
    }

    private static boolean hasValue(String[] args, int i) {
        return i + 1 < args.length && !args[i + 1].startsWith("-");
    }

    private static String requireValue(String[] args, int i, String opt) {
        if (i >= args.length) {
            throw new IllegalArgumentException("option " + opt + " requires a value");
        }
        return args[i];
    }
}
