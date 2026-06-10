package org.basic2asm;

import org.basic2asm.chip.ChipDefinition;
import org.basic2asm.chip.ChipRegistry;
import org.basic2asm.config.Config;
import org.basic2asm.diag.DiagnosticReporter;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * End-to-end tests that drive the full pipeline through {@link Transpiler} and
 * assert on properties of the generated MPASM.
 */
public class TranspilerTest {

    private Transpiler.Output compile(String src, String chipName, Config cfg) {
        ChipDefinition chip = ChipRegistry.get(chipName);
        assertNotNull("chip " + chipName + " must be supported", chip);
        DiagnosticReporter reporter = new DiagnosticReporter("test.bas", src);
        Transpiler.Output out = Transpiler.compile(src, chip, cfg, reporter);
        if (out == null) {
            StringBuilder sb = new StringBuilder("compilation failed:\n");
            reporter.getDiagnostics().forEach(d -> sb.append("  ").append(d).append('\n'));
            fail(sb.toString());
        }
        return out;
    }

    private Config cfg4mhz() {
        Config c = new Config();
        c.clockHz = 4_000_000L;
        return c;
    }

    @Test
    public void blinkGeneratesValidStructure() {
        String src = "OUTPUT RB0\n"
                + "top:\n"
                + "  HIGH RB0\n"
                + "  DELAY 100\n"
                + "  LOW RB0\n"
                + "  DELAY 100\n"
                + "  GOTO top\n";
        Transpiler.Output out = compile(src, "PIC16F84A", cfg4mhz());
        assertTrue(out.asm.contains("LIST    p=16f84a"));
        assertTrue(out.asm.contains("#include <p16f84a.inc>"));
        assertTrue(out.asm.contains("__CONFIG"));
        assertTrue(out.asm.contains("bsf     PORTB, 0"));   // HIGH RB0
        assertTrue(out.asm.contains("bcf     PORTB, 0"));   // LOW RB0
        assertTrue(out.asm.contains("bcf     TRISB, 0"));   // OUTPUT direction
        assertTrue(out.asm.trim().endsWith("END"));
    }

    @Test
    public void arithmeticEmitsMultiplyRoutine() {
        String src = "DIM a AS BYTE\n"
                + "DIM b AS BYTE\n"
                + "a = 3\n"
                + "b = a * a + 1\n";
        Transpiler.Output out = compile(src, "PIC16F84A", cfg4mhz());
        assertTrue("multiply runtime should be emitted", out.asm.contains("_mul8"));
        assertTrue(out.asm.contains("addwf"));
    }

    @Test
    public void constantsAreFolded() {
        String src = "CONST K = 2 + 3 * 4\n"
                + "DIM x AS BYTE\n"
                + "x = K\n";
        Transpiler.Output out = compile(src, "PIC16F84A", cfg4mhz());
        // 2 + 3*4 = 14 = 0x0E ; should appear as an immediate load, no runtime mul.
        assertTrue(out.asm.contains("0x0E"));
        assertFalse(out.asm.contains("_mul8"));
    }

    @Test
    public void baselineUsesTrisInstructionAndRetlw() {
        String src = "OUTPUT GP0\n"
                + "loopy:\n"
                + "  TOGGLE GP0\n"
                + "  GOTO loopy\n";
        Transpiler.Output out = compile(src, "PIC12F509", cfg4mhz());
        assertTrue("baseline must use the TRIS instruction", out.asm.contains("tris    6"));
        assertFalse("baseline must not use banksel", out.asm.contains("banksel"));
    }

    @Test
    public void hardwareUartRejectedOnPic16f84a() {
        Config c = cfg4mhz();
        c.uartMode = Config.UartMode.HARDWARE;
        ChipDefinition chip = ChipRegistry.get("PIC16F84A");
        DiagnosticReporter reporter = new DiagnosticReporter("t.bas", "UARTINIT\n");
        Transpiler.Output out = Transpiler.compile("UARTINIT\n", chip, c, reporter);
        assertNull(out);
        assertTrue(reporter.hasErrors());
    }

    @Test
    public void undefinedLabelIsReported() {
        ChipDefinition chip = ChipRegistry.get("PIC16F84A");
        String src = "GOTO nowhere\n";
        DiagnosticReporter reporter = new DiagnosticReporter("t.bas", src);
        Transpiler.Output out = Transpiler.compile(src, chip, cfg4mhz(), reporter);
        assertNull(out);
        assertTrue(reporter.hasErrors());
    }

    @Test
    public void interruptHandlerInstalledAtVector() {
        String src = "DIM t AS BYTE\n"
                + "POKE INTCON, 0xA0\n"
                + "idle:\n  GOTO idle\n"
                + "SUB ISR\n  t = t + 1\nEND SUB\n";
        Transpiler.Output out = compile(src, "PIC16F84A", cfg4mhz());
        assertTrue(out.asm.contains("org     0x0004"));
        assertTrue(out.asm.contains("retfie"));
        assertTrue(out.asm.contains("w_temp"));
    }

    @Test
    public void registryHasPopularChips() {
        assertTrue(ChipRegistry.isSupported("PIC16F84A"));
        assertTrue(ChipRegistry.isSupported("PIC12F509"));
        assertTrue(ChipRegistry.isSupported("PIC16F877A"));
        assertTrue(ChipRegistry.isSupported("PIC16F628A"));
        assertTrue(ChipRegistry.supportedNames().size() >= 15);
    }

    @Test
    public void clockParsingHandlesUnits() {
        assertEquals(4_000_000L, Config.parseClock("4MHz"));
        assertEquals(20_000_000L, Config.parseClock("20 MHz"));
        assertEquals(32_768L, Config.parseClock("32768"));
        assertEquals(100_000L, Config.parseClock("100kHz"));
    }
}
