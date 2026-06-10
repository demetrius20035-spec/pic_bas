package org.basic2asm.chip;

/**
 * The two CPU cores covered by this transpiler.
 *
 * <ul>
 *   <li>{@link #BASELINE} – 12-bit instruction word (PIC10, PIC12F5xx, PIC16F5x).
 *       No interrupts, 2-level hardware stack, I/O direction via the {@code TRIS}
 *       and {@code OPTION} instructions, no {@code ADDLW}/{@code SUBLW}/{@code RETURN}.</li>
 *   <li>{@link #MIDRANGE} – 14-bit instruction word (PIC12F6xx, PIC16F).
 *       Interrupt vector at 0x04, 8-level stack, banked SFRs, full instruction set.</li>
 * </ul>
 */
public enum CoreType {
    BASELINE,
    MIDRANGE;

    public boolean isBaseline() { return this == BASELINE; }
    public boolean isMidrange() { return this == MIDRANGE; }
}
