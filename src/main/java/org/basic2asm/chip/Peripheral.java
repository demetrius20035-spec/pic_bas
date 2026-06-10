package org.basic2asm.chip;

/**
 * Hardware peripheral modules a chip may provide. The code generator queries
 * {@link ChipDefinition#has(Peripheral)} before emitting peripheral-specific code
 * so that, e.g., a hardware-UART request on a PIC16F84A is rejected with a clear
 * diagnostic instead of generating invalid assembly.
 */
public enum Peripheral {
    GPIO,        // digital I/O ports (always present)
    TIMER0,      // TMR0 8-bit timer/counter
    TIMER1,      // TMR1 16-bit timer
    TIMER2,      // TMR2 8-bit timer with period register
    UART_HW,     // hardware USART/EUSART
    ADC,         // analog-to-digital converter
    DAC,         // digital-to-analog converter (rare on classic PIC12/16)
    CCP,         // capture/compare/PWM
    COMPARATOR,  // analog comparator(s)
    EEPROM,      // on-chip data EEPROM
    INTERRUPTS   // interrupt logic (midrange only)
}
