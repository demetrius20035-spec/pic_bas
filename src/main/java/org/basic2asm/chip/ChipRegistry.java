package org.basic2asm.chip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.basic2asm.chip.Peripheral.*;

/**
 * Central catalogue of supported chips. Adding a new microcontroller is a matter
 * of registering one more {@link ChipDefinition} here; the rest of the toolchain
 * is data-driven from these definitions.
 *
 * <p>The emphasis, per the project goals, is on the popular classic PIC12/PIC16
 * parts (PIC16F84A, PIC12F509, ...). Less common parts are included where their
 * geometry is well known.</p>
 */
public final class ChipRegistry {

    private static final Map<String, ChipDefinition> CHIPS = new LinkedHashMap<>();

    private ChipRegistry() { }

    static {
        registerBaseline();
        registerMidrangePic16();
        registerMidrangePic12();
    }

    private static void put(ChipDefinition d) {
        CHIPS.put(d.getName().toUpperCase(), d);
    }

    public static ChipDefinition get(String name) {
        if (name == null) return null;
        return CHIPS.get(name.toUpperCase());
    }

    public static boolean isSupported(String name) {
        return get(name) != null;
    }

    public static List<String> supportedNames() {
        List<String> names = new ArrayList<>(CHIPS.keySet());
        Collections.sort(names);
        return names;
    }

    public static List<ChipDefinition> all() {
        return new ArrayList<>(CHIPS.values());
    }

    // ------------------------------------------------------------------
    // Port helpers
    // ------------------------------------------------------------------

    private static PortInfo portA(int pins) { return new PortInfo("PORTA", "RA", "TRISA", -1, pins); }
    private static PortInfo portB(int pins) { return new PortInfo("PORTB", "RB", "TRISB", -1, pins); }
    private static PortInfo portC(int pins) { return new PortInfo("PORTC", "RC", "TRISC", -1, pins); }
    private static PortInfo portD(int pins) { return new PortInfo("PORTD", "RD", "TRISD", -1, pins); }
    private static PortInfo portE(int pins) { return new PortInfo("PORTE", "RE", "TRISE", -1, pins); }
    /** Baseline GPIO whose direction is configured via the TRIS instruction. */
    private static PortInfo gpio(int fileAddr, int pins) {
        return new PortInfo("GPIO", "GP", null, fileAddr, pins);
    }
    /** Midrange 12F GPIO whose direction is the memory-mapped TRISIO register. */
    private static PortInfo gpioIo(int pins) {
        return new PortInfo("GPIO", "GP", "TRISIO", -1, pins);
    }

    private static ChipDefinition.Builder midrange(String name) {
        return new ChipDefinition.Builder(name, CoreType.MIDRANGE).add(INTERRUPTS, TIMER0);
    }

    private static ChipDefinition.Builder baseline(String name) {
        return new ChipDefinition.Builder(name, CoreType.BASELINE).add(TIMER0);
    }

    // Common fuse profiles -------------------------------------------------

    private static void classicMidrangeOsc(ChipDefinition.Builder b) {
        b.osc("LP", "_LP_OSC").osc("XT", "_XT_OSC").osc("HS", "_HS_OSC").osc("EXTRC", "_RC_OSC");
    }

    private static void internalOscMidrange(ChipDefinition.Builder b) {
        b.osc("LP", "_LP_OSC").osc("XT", "_XT_OSC").osc("HS", "_HS_OSC")
         .osc("INTRC", "_INTRC_OSC_NOCLKOUT").osc("EXTRC", "_EXTRC_OSC_NOCLKOUT");
    }

    private static void baselineOsc(ChipDefinition.Builder b) {
        b.osc("LP", "_LP_OSC").osc("XT", "_XT_OSC").osc("HS", "_HS_OSC")
         .osc("INTRC", "_IntRC_OSC").osc("EXTRC", "_ExtRC_OSC");
    }

    // ------------------------------------------------------------------
    // Baseline (12-bit core) parts
    // ------------------------------------------------------------------

    private static void registerBaseline() {
        // PIC10F200 family – tiny 6-pin parts, single 4-pin GPIO.
        ChipDefinition.Builder p10f200 = baseline("PIC10F200").program(256)
                .gpr(0x10, 0x1F).port(gpio(0x06, 4));
        baselineOsc(p10f200);
        put(p10f200.baseConfig("_WDT_OFF & _CP_OFF & _MCLRE_OFF").configAddress(0xFFF).build());

        ChipDefinition.Builder p10f202 = baseline("PIC10F202").program(512)
                .gpr(0x08, 0x1F).port(gpio(0x06, 4));
        baselineOsc(p10f202);
        put(p10f202.baseConfig("_WDT_OFF & _CP_OFF & _MCLRE_OFF").configAddress(0xFFF).build());

        // PIC12F5xx family.
        ChipDefinition.Builder p12f508 = baseline("PIC12F508").program(512)
                .gpr(0x07, 0x1F).port(gpio(0x06, 6));
        baselineOsc(p12f508);
        put(p12f508.baseConfig("_WDT_OFF & _CP_OFF & _MCLRE_ON").configAddress(0xFFF).build());

        ChipDefinition.Builder p12f509 = baseline("PIC12F509").program(1024)
                .gpr(0x07, 0x1F).port(gpio(0x06, 6)).add(EEPROM);
        baselineOsc(p12f509);
        put(p12f509.baseConfig("_WDT_OFF & _CP_OFF & _MCLRE_ON").configAddress(0xFFF).build());

        ChipDefinition.Builder p12f510 = baseline("PIC12F510").program(1024)
                .gpr(0x10, 0x3F).port(gpio(0x06, 6)).adc(0, 0).add(COMPARATOR);
        baselineOsc(p12f510);
        put(p12f510.baseConfig("_WDT_OFF & _CP_OFF & _MCLRE_ON").configAddress(0xFFF).build());

        // PIC16F5x baseline.
        ChipDefinition.Builder p16f54 = baseline("PIC16F54").program(512)
                .gpr(0x07, 0x1F).port(portA(4)).port(portB(8));
        baselineOsc(p16f54);
        put(p16f54.baseConfig("_WDT_OFF & _CP_OFF").configAddress(0xFFF).build());

        ChipDefinition.Builder p16f57 = baseline("PIC16F57").program(2048)
                .gpr(0x08, 0x7F).port(portA(4)).port(portB(8)).port(portC(8));
        baselineOsc(p16f57);
        put(p16f57.baseConfig("_WDT_OFF & _CP_OFF").configAddress(0xFFF).build());

        // PIC16F505/506 baseline with three ports.
        ChipDefinition.Builder p16f505 = baseline("PIC16F505").program(1024)
                .gpr(0x08, 0x1F).port(portB(6)).port(portC(6));
        baselineOsc(p16f505);
        put(p16f505.baseConfig("_WDT_OFF & _CP_OFF & _MCLRE_ON").configAddress(0xFFF).build());
    }

    // ------------------------------------------------------------------
    // Midrange PIC16 parts
    // ------------------------------------------------------------------

    private static void registerMidrangePic16() {
        // PIC16F84 / 84A – the classic teaching part.
        ChipDefinition.Builder p16f84 = midrange("PIC16F84").program(1024)
                .gpr(0x0C, 0x4F).port(portA(5)).port(portB(8)).add(EEPROM);
        classicMidrangeOsc(p16f84);
        put(p16f84.baseConfig("_WDT_OFF & _PWRTE_ON & _CP_OFF").build());

        ChipDefinition.Builder p16f84a = midrange("PIC16F84A").program(1024)
                .gpr(0x0C, 0x4F).port(portA(5)).port(portB(8)).add(EEPROM);
        classicMidrangeOsc(p16f84a);
        put(p16f84a.baseConfig("_WDT_OFF & _PWRTE_ON & _CP_OFF").build());

        ChipDefinition.Builder p16f628a = midrange("PIC16F628A").program(2048)
                .gpr(0x20, 0x7F).port(portA(8)).port(portB(8))
                .add(TIMER1, TIMER2, CCP, COMPARATOR, EEPROM)
                .usart("TXREG", "RCREG");
        p16f628a.osc("LP", "_LP_OSC").osc("XT", "_XT_OSC").osc("HS", "_HS_OSC")
                .osc("INTRC", "_INTOSC_OSC_NOCLKOUT").osc("EXTRC", "_EXTCLK_OSC");
        put(p16f628a.baseConfig("_WDT_OFF & _PWRTE_ON & _MCLRE_ON & _BOREN_OFF & _LVP_OFF & _CP_OFF").build());

        ChipDefinition.Builder p16f627a = midrange("PIC16F627A").program(1024)
                .gpr(0x20, 0x7F).port(portA(8)).port(portB(8))
                .add(TIMER1, TIMER2, CCP, COMPARATOR, EEPROM)
                .usart("TXREG", "RCREG");
        p16f627a.osc("LP", "_LP_OSC").osc("XT", "_XT_OSC").osc("HS", "_HS_OSC")
                .osc("INTRC", "_INTOSC_OSC_NOCLKOUT").osc("EXTRC", "_EXTCLK_OSC");
        put(p16f627a.baseConfig("_WDT_OFF & _PWRTE_ON & _MCLRE_ON & _BOREN_OFF & _LVP_OFF & _CP_OFF").build());

        ChipDefinition.Builder p16f648a = midrange("PIC16F648A").program(4096)
                .gpr(0x20, 0xEF).port(portA(8)).port(portB(8))
                .add(TIMER1, TIMER2, CCP, COMPARATOR, EEPROM)
                .usart("TXREG", "RCREG");
        p16f648a.osc("LP", "_LP_OSC").osc("XT", "_XT_OSC").osc("HS", "_HS_OSC")
                .osc("INTRC", "_INTOSC_OSC_NOCLKOUT").osc("EXTRC", "_EXTCLK_OSC");
        put(p16f648a.baseConfig("_WDT_OFF & _PWRTE_ON & _MCLRE_ON & _BOREN_OFF & _LVP_OFF & _CP_OFF").build());

        // PIC16F87x(A) – full-feature with ADC + USART + CCP.
        ChipDefinition.Builder p16f877a = midrange("PIC16F877A").program(8192)
                .gpr(0x20, 0x7F).port(portA(6)).port(portB(8)).port(portC(8)).port(portD(8)).port(portE(3))
                .add(TIMER1, TIMER2, CCP, COMPARATOR, EEPROM).adc(8, 10)
                .usart("TXREG", "RCREG");
        classicMidrangeOsc(p16f877a);
        put(p16f877a.baseConfig("_WDT_OFF & _PWRTE_ON & _BOREN_OFF & _LVP_OFF & _CPD_OFF & _CP_OFF").build());

        ChipDefinition.Builder p16f876a = midrange("PIC16F876A").program(8192)
                .gpr(0x20, 0x7F).port(portA(6)).port(portB(8)).port(portC(8))
                .add(TIMER1, TIMER2, CCP, COMPARATOR, EEPROM).adc(5, 10)
                .usart("TXREG", "RCREG");
        classicMidrangeOsc(p16f876a);
        put(p16f876a.baseConfig("_WDT_OFF & _PWRTE_ON & _BOREN_OFF & _LVP_OFF & _CPD_OFF & _CP_OFF").build());

        ChipDefinition.Builder p16f873a = midrange("PIC16F873A").program(4096)
                .gpr(0x20, 0x7F).port(portA(6)).port(portB(8)).port(portC(8))
                .add(TIMER1, TIMER2, CCP, COMPARATOR, EEPROM).adc(5, 10)
                .usart("TXREG", "RCREG");
        classicMidrangeOsc(p16f873a);
        put(p16f873a.baseConfig("_WDT_OFF & _PWRTE_ON & _BOREN_OFF & _LVP_OFF & _CPD_OFF & _CP_OFF").build());

        // PIC16F88 – internal osc, ADC, USART.
        ChipDefinition.Builder p16f88 = midrange("PIC16F88").program(4096)
                .gpr(0x20, 0x7F).port(portA(8)).port(portB(8))
                .add(TIMER1, TIMER2, CCP, COMPARATOR, EEPROM).adc(7, 10)
                .usart("TXREG", "RCREG");
        p16f88.osc("LP", "_LP_OSC").osc("XT", "_XT_OSC").osc("HS", "_HS_OSC")
              .osc("INTRC", "_INTRC_IO").osc("EXTRC", "_EXTRC_IO");
        put(p16f88.baseConfig("_WDT_OFF & _PWRTE_ON & _MCLR_ON & _BOREN_OFF & _LVP_OFF & _CP_OFF").build());

        // PIC16F716 – ADC, CCP, no USART.
        ChipDefinition.Builder p16f716 = midrange("PIC16F716").program(2048)
                .gpr(0x20, 0x7F).port(portA(5)).port(portB(8))
                .add(TIMER1, TIMER2, CCP).adc(4, 8);
        classicMidrangeOsc(p16f716);
        put(p16f716.baseConfig("_WDT_OFF & _PWRTE_ON & _BOREN_OFF & _CP_OFF").build());

        // PIC16F690 / 16F684 (enhanced midrange-ish, treated as midrange).
        ChipDefinition.Builder p16f690 = midrange("PIC16F690").program(4096)
                .gpr(0x20, 0x7F).port(portA(6)).port(portB(8)).port(portC(8))
                .add(TIMER1, TIMER2, CCP, COMPARATOR, EEPROM).adc(12, 10)
                .usart("TXREG", "RCREG");
        p16f690.osc("INTRC", "_INTRC_OSC_NOCLKOUT").osc("XT", "_XT_OSC")
               .osc("HS", "_HS_OSC").osc("LP", "_LP_OSC").osc("EXTRC", "_EXTRC_OSC_NOCLKOUT");
        put(p16f690.baseConfig("_WDT_OFF & _PWRTE_ON & _MCLRE_ON & _BOR_OFF & _CP_OFF & _CPD_OFF").build());

        ChipDefinition.Builder p16f684 = midrange("PIC16F684").program(2048)
                .gpr(0x20, 0x7F).port(portA(6)).port(portC(6))
                .add(TIMER1, TIMER2, CCP, COMPARATOR, EEPROM).adc(8, 10);
        p16f684.osc("INTRC", "_INTOSCIO").osc("XT", "_XT_OSC")
               .osc("HS", "_HS_OSC").osc("LP", "_LP_OSC").osc("EXTRC", "_EXTRCIO");
        put(p16f684.baseConfig("_WDT_OFF & _PWRTE_ON & _MCLRE_ON & _BOD_OFF & _CP_OFF & _CPD_OFF").build());
    }

    // ------------------------------------------------------------------
    // Midrange PIC12 parts
    // ------------------------------------------------------------------

    private static void registerMidrangePic12() {
        ChipDefinition.Builder p12f629 = midrange("PIC12F629").program(1024)
                .gpr(0x20, 0x5F).port(gpioIo(6)).add(TIMER1, COMPARATOR, EEPROM);
        // GPIO on midrange 12F is a memory-mapped register named GPIO with TRISIO.
        internalOscMidrange(p12f629);
        put(p12f629.baseConfig("_WDT_OFF & _PWRTE_ON & _MCLRE_OFF & _BODEN_OFF & _CP_OFF & _CPD_OFF").build());

        ChipDefinition.Builder p12f675 = midrange("PIC12F675").program(1024)
                .gpr(0x20, 0x5F).port(gpioIo(6)).add(TIMER1, COMPARATOR, EEPROM).adc(4, 10);
        internalOscMidrange(p12f675);
        put(p12f675.baseConfig("_WDT_OFF & _PWRTE_ON & _MCLRE_OFF & _BODEN_OFF & _CP_OFF & _CPD_OFF").build());

        ChipDefinition.Builder p12f683 = midrange("PIC12F683").program(2048)
                .gpr(0x20, 0x7F).port(gpioIo(6)).add(TIMER1, TIMER2, CCP, COMPARATOR, EEPROM).adc(4, 10);
        p12f683.osc("INTRC", "_INTOSCIO").osc("XT", "_XT_OSC")
               .osc("HS", "_HS_OSC").osc("LP", "_LP_OSC").osc("EXTRC", "_EXTRCIO");
        put(p12f683.baseConfig("_WDT_OFF & _PWRTE_ON & _MCLRE_OFF & _BOD_OFF & _CP_OFF & _CPD_OFF").build());
    }
}
