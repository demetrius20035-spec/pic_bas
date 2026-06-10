# basic2asm

**A console BASIC → assembler transpiler for Microchip PIC12 / PIC16 microcontrollers.**

`basic2asm` compiles a small, friendly BASIC dialect into [MPASM](https://www.microchip.com/)-compatible
assembly for classic 8-bit PIC microcontrollers — with an emphasis on the popular older parts such as the
**PIC16F84A** and **PIC12F509**. It is written in **Java 8**, has **zero runtime dependencies**, and is
designed to be modular so the community can add new chips and features easily.

> Консольный транспилятор с языка BASIC в ассемблер MPASM для микроконтроллеров Microchip PIC12/PIC16.
> Написан на Java 8, без внешних зависимостей. Подробности использования — ниже.

---

## Features

- **Two cores, many chips.** Baseline (12-bit, e.g. PIC10F/PIC12F5xx/PIC16F5x) and midrange
  (14-bit, e.g. PIC16F84A, PIC16F628A, PIC16F877A, PIC12F675). Run `--list-chips` for the full list.
- **Hardware-aware code generation.** Per-chip memory geometry, RAM banking (`banksel`), the baseline
  `TRIS`/`OPTION` instructions vs. midrange memory-mapped TRIS, comparator/analog disabling, the
  2-level vs. 8-level call stack, and flash/RAM capacity checks.
- **Peripherals.** Digital I/O, software **UART** (bit-banged, works on any chip) and hardware **USART**,
  **ADC**, timers/**interrupts** (TMR0/INT via an `ISR` handler), plus raw register `PEEK`/`POKE`.
- **Flexible configuration.** Target chip, clock frequency, oscillator mode, fuse/config word,
  optimisation preference, UART parameters and ADC resolution — all settable on the command line or via
  a JSON config file.
- **Good diagnostics.** Errors and warnings carry the source line/column and show the offending line.
- **Clean pipeline.** Distinct lexer → parser → semantic analyzer → code generator phases.
- **Outputs.** `.asm` (MPASM), optional `.map` (RAM/flash map) and `.lst` (source listing).

## Requirements

- **JDK 8** or newer (the build targets Java 8 bytecode).
- **Maven 3.6+** to build.
- To actually assemble the output: Microchip **MPASM** (e.g. via MPLAB IDE/MPLAB X) or **gpasm**
  (the GNU PIC assembler). `basic2asm` produces the `.asm`; it does not assemble it itself.

## Build

```bash
mvn package
# produces target/basic2asm.jar (an executable, self-contained jar)
```

Run the tests with `mvn test`.

## Quick start

```bash
java -jar target/basic2asm.jar --chip PIC16F84A --input examples/blink.bas \
     --output examples/blink.asm --clock 4MHz --optimize size
```

Then assemble with MPASM or gpasm, for example:

```bash
gpasm -p p16f84a examples/blink.asm        # produces blink.hex
```

List everything the tool supports:

```bash
java -jar target/basic2asm.jar --list-chips
```

## Command-line options

| Option | Description |
| --- | --- |
| `--chip <NAME>` | Target microcontroller, e.g. `PIC16F84A`. **Required.** |
| `-i, --input <FILE>` | BASIC source file (`.bas`). **Required.** |
| `-o, --output <FILE>` | Output assembly file (default: `<input>.asm`). |
| `--clock <FREQ>` | Oscillator frequency: `4MHz`, `20MHz`, `32768`, `100kHz`… (default `4MHz`). |
| `--osc <MODE>` | Oscillator mode: `XT`, `HS`, `LP`, `INTRC`, `EXTRC` (default: auto from clock). |
| `--optimize <size\|speed>` | Optimisation preference (default `size`). |
| `--comments` / `--no-comments` | Emit debug comments in the assembly (default on). |
| `--uart-mode <software\|hardware>` | UART implementation (default `software`). |
| `--uart-baud <N>` | UART baud rate (default `9600`). |
| `--uart-tx <PIN>` | Software-UART TX pin, e.g. `RB2` (default `RB2`). |
| `--uart-rx <PIN>` | Software-UART RX pin (default `RB1`). |
| `--adc-bits <N>` | ADC result width to use, `8` or `10` (default `8`). |
| `--config-word <EXPR>` | Raw `__CONFIG` expression override. |
| `--config <FILE.json>` | Load options from a JSON config file. |
| `--list [FILE]` | Also write a source listing. |
| `--map [FILE]` | Also write a memory map. |
| `--list-chips` | Print all supported chips and exit. |
| `--version` / `-h, --help` | Version / help. |

### JSON configuration file

Command-line flags override file values, so a config file is a convenient way to store project defaults.
See [`examples/config.json`](examples/config.json):

```json
{
  "chip": "PIC16F628A",
  "clock": "4MHz",
  "optimize": "size",
  "uartMode": "hardware",
  "uartBaud": 9600
}
```

```bash
java -jar target/basic2asm.jar --config examples/config.json --input examples/uart_hello.bas
```

## The BASIC language

See **[docs/LANGUAGE.md](docs/LANGUAGE.md)** for the full reference. A flavour:

```basic
' Blink an LED on RB0
DIM i AS BYTE
OUTPUT RB0
blink:
  HIGH RB0
  DELAY 500          ' milliseconds
  LOW RB0
  DELAY 500
  GOTO blink
```

Highlights:

- **Variables**: `DIM name AS BYTE` (8-bit; `BIT` and `WORD` accepted — see limitations). `CONST` folds at compile time.
- **Control flow**: `IF/ELSEIF/ELSE/ENDIF`, `FOR…NEXT`, `WHILE…WEND`, `DO…LOOP [WHILE|UNTIL]`, `GOTO`, labels.
- **Subroutines**: `SUB`/`END SUB`, `FUNCTION`/`END FUNCTION` with `RETURN`, `CALL`, and `GOSUB`/`RETURN`.
- **I/O**: `OUTPUT`/`INPUT` a pin or port, `HIGH`/`LOW`/`TOGGLE` a pin, read a pin (`RB0`) or whole port (`PORTB`).
- **Peripherals**: `UARTINIT`, `UARTWRITE`, `PRINT`, `ADC(channel)`, `DELAY`/`DELAYUS`, `PEEK`/`POKE`,
  and a special `SUB ISR` installed at the interrupt vector.
- **Operators**: `+ - * /  MOD`, `AND OR XOR NOT`, `<< >>`, comparisons `=  <>  <  <=  >  >=`.

## Supported chips

Run `java -jar target/basic2asm.jar --list-chips`. Currently included:

**Baseline (12-bit):** PIC10F200, PIC10F202, PIC12F508, PIC12F509, PIC12F510, PIC16F54, PIC16F57, PIC16F505.

**Midrange (14-bit):** PIC16F84, PIC16F84A, PIC16F627A, PIC16F628A, PIC16F648A, PIC16F716, PIC16F684,
PIC16F690, PIC16F873A, PIC16F876A, PIC16F877A, PIC16F88, PIC12F629, PIC12F675, PIC12F683.

Adding a chip is usually just one entry in
[`ChipRegistry`](src/main/java/org/basic2asm/chip/ChipRegistry.java) — see
[docs/ADDING_A_CHIP.md](docs/ADDING_A_CHIP.md).

## Architecture

```
            +---------+    +----------+    +-------------------+    +----------------+
  BASIC --> |  Lexer  | -> |  Parser  | -> | SemanticAnalyzer  | -> | CodeGenerator  | --> MPASM .asm
            +---------+    +----------+    +-------------------+    +----------------+
              tokens          AST              validation            chip-aware emit
```

| Package | Responsibility |
| --- | --- |
| `org.basic2asm.lexer` | Tokeniser (`Lexer`, `Token`, `TokenType`). |
| `org.basic2asm.ast` | AST node types (`Expr`, `Stmt`, `Program`, `VarType`). |
| `org.basic2asm.parser` | Recursive-descent `Parser`. |
| `org.basic2asm.sema` | `SemanticAnalyzer` (label/arity/constant checks). |
| `org.basic2asm.chip` | Chip model and catalogue (`ChipDefinition`, `ChipRegistry`, `Peripheral`, `PortInfo`). |
| `org.basic2asm.codegen` | `CodeGenerator`, `AsmBuilder`, `Symbols` (the chip-aware emitter and runtime library). |
| `org.basic2asm.config` | `Config`, `ConfigLoader`, in-tree `Json` parser. |
| `org.basic2asm.cli` | `ArgParser`. |
| `org.basic2asm.diag` | `Diagnostic`, `DiagnosticReporter`, `CompileException`. |
| `org.basic2asm` | `Main` (CLI) and `Transpiler` (library API). |

### Library API

```java
Config cfg = new Config();
cfg.clockHz = 4_000_000L;
DiagnosticReporter reporter = new DiagnosticReporter("prog.bas", source);
ChipDefinition chip = ChipRegistry.get("PIC16F84A");
Transpiler.Output out = Transpiler.compile(source, chip, cfg, reporter);
if (out != null) {
    System.out.println(out.asm);
}
```

## Limitations & notes

These are honest, documented constraints — and good first contributions:

- **8-bit arithmetic.** The expression engine is 8-bit. `WORD` variables are accepted but downgraded to a
  byte with a warning. Multiply/divide use iterative runtime routines (correct, not the fastest).
- **Delays are approximate.** Busy-wait loops are calibrated from the clock to within a few percent; they
  do not account for interrupt latency. For exact timing, use a hardware timer via `POKE`/`ISR`.
- **Call depth on baseline parts.** Baseline cores have only a 2-level hardware stack. `PRINT` (and deep
  `GOSUB`/`CALL` nesting) can exceed it; the tool warns. Prefer midrange parts for UART-heavy code.
- **Code pages.** Programs are assumed to fit in a single code page unless your chip is large; very large
  midrange programs may need manual `pagesel`.
- **ADC** code generation is implemented for the PIC16F87xA family and PIC12F675; other ADC parts emit a
  warning. Extending it is straightforward (see the `adcStyle` switch in `CodeGenerator`).
- **Fuse tokens** default to common Microchip names and can be fully overridden with `--config-word`.

## Contributing

Contributions are very welcome — new chips, peripherals, optimisations and language features.
See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT — see [LICENSE](LICENSE).
