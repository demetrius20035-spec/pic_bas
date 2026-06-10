package org.basic2asm.chip;

/**
 * Describes one I/O port of a chip and how its pins are named in BASIC source.
 *
 * <p>For example a PIC16F84A has PORTB (prefix {@code RB}, TRIS register
 * {@code TRISB}, 8 pins); a baseline PIC12F509 has a single {@code GPIO} port
 * (prefix {@code GP}) whose direction is set with the {@code TRIS} instruction
 * rather than a memory-mapped TRIS register.</p>
 */
public final class PortInfo {

    /** Data register name as known to the MPASM include file, e.g. PORTB / GPIO. */
    public final String register;
    /** Pin-name prefix used in BASIC, e.g. "RB" so that RB0..RB7 map to this port. */
    public final String pinPrefix;
    /** TRIS register name (midrange), e.g. TRISB; null on baseline cores. */
    public final String trisRegister;
    /** Operand of the baseline {@code TRIS} instruction (the port file address); -1 if N/A. */
    public final int trisInstrTarget;
    /** Number of usable pins on this port (0..pinCount-1). */
    public final int pinCount;

    public PortInfo(String register, String pinPrefix, String trisRegister,
                    int trisInstrTarget, int pinCount) {
        this.register = register;
        this.pinPrefix = pinPrefix;
        this.trisRegister = trisRegister;
        this.trisInstrTarget = trisInstrTarget;
        this.pinCount = pinCount;
    }
}
