package org.basic2asm.config;

/**
 * The fully-resolved set of transpiler options, assembled from command-line
 * arguments and/or a JSON configuration file. A single immutable-ish bag passed
 * to the code generator.
 */
public final class Config {

    public enum Optimize { SIZE, SPEED }
    public enum UartMode { SOFTWARE, HARDWARE }

    // I/O
    public String chip;
    public String inputFile;
    public String outputFile;
    public String listFile;   // null => not generated
    public String mapFile;    // null => not generated

    // Clock / oscillator
    public long clockHz = 4_000_000L;
    public String oscMode;    // XT/HS/LP/INTRC/EXTRC; null => auto-select from clockHz

    // Optimisation & output style
    public Optimize optimize = Optimize.SIZE;
    public boolean comments = true;

    // UART
    public UartMode uartMode = UartMode.SOFTWARE;
    public int uartBaud = 9600;
    public String uartTxPin = "RB2";  // used for software UART
    public String uartRxPin = "RB1";

    // ADC
    public int adcResolution = 8;     // bits of result the program will use (8 or 10)

    // Fuses
    public String rawConfig;          // full __CONFIG expression override; null => derive

    /** Parse a human clock string like "4MHz", "32768", "20 MHz" into Hz. */
    public static long parseClock(String text) {
        String t = text.trim().toLowerCase().replace(" ", "");
        double mult = 1.0;
        if (t.endsWith("mhz")) { mult = 1_000_000.0; t = t.substring(0, t.length() - 3); }
        else if (t.endsWith("khz")) { mult = 1_000.0; t = t.substring(0, t.length() - 3); }
        else if (t.endsWith("hz")) { mult = 1.0; t = t.substring(0, t.length() - 2); }
        return Math.round(Double.parseDouble(t) * mult);
    }
}
