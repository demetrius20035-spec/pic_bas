' ---------------------------------------------------------------
' interrupt_counter.bas - Count TMR0 overflow interrupts.
' The special SUB named ISR is installed at the interrupt vector.
' ---------------------------------------------------------------
DIM ticks AS BYTE

' OPTION_REG: TMR0 from internal clock, prescaler 1:256
POKE OPTION_REG, 0x07
' INTCON: enable global + TMR0 interrupts (GIE | T0IE)
POKE INTCON, 0xA0

OUTPUT RB0
ticks = 0

idle:
  GOTO idle

SUB ISR
  ticks = ticks + 1
  TOGGLE RB0
  POKE INTCON, 0xA0   ' re-arm (clear T0IF, keep enables)
END SUB
