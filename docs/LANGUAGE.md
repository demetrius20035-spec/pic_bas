# basic2asm BASIC language reference

This document describes the BASIC dialect accepted by `basic2asm`. It is a small, line-oriented
language designed to map cleanly onto 8-bit PIC assembly.

## Lexical structure

- **Lines** end statements. A colon `:` also separates statements on one line.
- **Comments** start with `REM` or an apostrophe `'` and run to the end of the line.
- **Case** is insensitive for keywords; identifiers are matched case-insensitively too.
- **Numbers**: decimal (`42`), hex (`0x2A` or `&H2A`), binary (`&B101010` or `%101010`), octal (`&O52`).
- **Strings**: `"double quoted"`, with escapes `\n \r \t \0 \\ \"` (used by `PRINT`).

## Data types

| Type | Width | Notes |
| --- | --- | --- |
| `BYTE` | 8-bit | The default and fully supported type. |
| `BIT`  | 8-bit storage | Treated as 0/non-zero for truth tests. |
| `WORD` | 16-bit storage | Accepted, but arithmetic is currently 8-bit (low byte) — emits a warning. |

## Declarations

```basic
DIM counter AS BYTE          ' declare a variable
DIM flag                     ' AS BYTE is optional
DIM seed AS BYTE = 7         ' with initialiser
CONST MAX = 100              ' compile-time constant (folded)
```

## Assignment

```basic
LET x = 5        ' LET is optional
x = x + 1
PORTB = 0xFF     ' write a whole port
RB0 = 1          ' write a single pin (same as HIGH RB0)
```

The assignment target may be a variable, a port register (`PORTB`, `GPIO`, …) or a single pin (`RB0`).

## Expressions and operators

From lowest to highest precedence:

1. `OR`, `XOR`
2. `AND`
3. `=`, `<>`
4. `<`, `<=`, `>`, `>=`
5. `<<`, `>>`
6. `+`, `-`
7. `*`, `/`, `MOD`
8. unary `-`, `NOT`

`AND`/`OR`/`XOR`/`NOT` are **bitwise** (the natural choice on an 8-bit MCU); comparisons yield `1` or `0`.
`NOT` is the bitwise complement.

Operands can be: numbers, constants, variables, a pin read (`RB0` → 0/1), a port read (`PORTB`),
`PEEK(addr)`, `ADC(channel)`, a function call, or a parenthesised sub-expression.

## Control flow

### IF

```basic
' single line
IF x > 10 THEN HIGH RB0
IF x > 10 THEN HIGH RB0 ELSE LOW RB0

' block form
IF x > 100 THEN
  PRINT "big"
ELSEIF x > 10 THEN
  PRINT "medium"
ELSE
  PRINT "small"
ENDIF            ' or END IF
```

### Loops

```basic
FOR i = 1 TO 10 STEP 2
  ...
NEXT i           ' the variable after NEXT is optional

WHILE x < 100
  x = x + 1
WEND

DO
  ...
LOOP UNTIL x = 0     ' also: DO WHILE/UNTIL ... LOOP, or a bare DO ... LOOP (infinite)
```

`STEP` may be negative when it is a constant.

### Labels, GOTO, GOSUB

```basic
loop_start:
  ...
  GOTO loop_start

  GOSUB beep
  ...
beep:
  HIGH RB1
  DELAY 50
  LOW RB1
  RETURN
```

> Label names must not collide with keywords (e.g. don't name a label `loop`).

## Subroutines and functions

```basic
CALL flash(3)

SUB flash(n)
  DIM k AS BYTE
  FOR k = 1 TO n
    HIGH RB0
    DELAY 100
    LOW RB0
    DELAY 100
  NEXT k
END SUB

FUNCTION square(x)
  RETURN x * x
END FUNCTION
```

Parameters are passed in statically-allocated RAM (so subroutines are **not re-entrant / recursive**).
A `FUNCTION` returns its value in the working register via `RETURN <expr>`.

## I/O

```basic
OUTPUT RB0       ' set a pin as output
INPUT RA1        ' set a pin as input
OUTPUT PORTB     ' set a whole port as output
HIGH RB0
LOW RB0
TOGGLE RB0
DIM s AS BYTE
s = RA1          ' read a pin (0/1)
s = PORTA        ' read a whole port
```

## Timing

```basic
DELAY 500        ' busy-wait ~500 ms (approximate, clock-calibrated)
DELAYUS 100      ' busy-wait ~100 us
```

## UART / serial

```basic
UARTINIT                       ' configure UART (software bit-bang by default)
UARTWRITE 65                   ' send one raw byte ('A')
PRINT "count="; n              ' formatted output; numbers print as decimal
PRINT "x=", x                  ' comma/semicolon separate items
PRINT "no newline";            ' trailing ';' suppresses the CR/LF
```

Choose the implementation and parameters on the command line: `--uart-mode`, `--uart-baud`, `--uart-tx`.
Hardware USART requires a chip that has one.

## ADC

```basic
DIM v AS BYTE
v = ADC(0)       ' read analog channel 0 (8-bit high byte of the result)
```

The channel must be a constant and within the chip's channel count.

## Raw register access

```basic
POKE OPTION_REG, 0x07     ' write any named SFR (uses banksel automatically)
POKE INTCON, 0xA0
DIM r AS BYTE
r = PEEK(0x05)            ' read a file register by address (bank 0)
```

## Interrupts

Define a subroutine named `ISR` (midrange parts only). It is installed at the interrupt vector
(`0x0004`) with automatic context save/restore and `RETFIE`. Enable the sources you want with `POKE`
into `INTCON`/`OPTION_REG`.

```basic
DIM ticks AS BYTE
POKE OPTION_REG, 0x07     ' TMR0, prescaler 1:256
POKE INTCON, 0xA0         ' GIE | T0IE
idle:
  GOTO idle

SUB ISR
  ticks = ticks + 1
  POKE INTCON, 0xA0       ' clear T0IF, keep enables
END SUB
```
