# Adding a new chip

`basic2asm` is data-driven: most chips are added by registering one `ChipDefinition` in
[`ChipRegistry`](../src/main/java/org/basic2asm/chip/ChipRegistry.java). No code-generator changes are
needed for a part that fits the existing peripheral model.

## 1. Gather the facts

From the device datasheet and the MPASM include file (`pXXXX.inc`) note:

- **Core**: baseline (12-bit) or midrange (14-bit).
- **MPASM processor name** (e.g. `16f88` → `LIST p=16f88`, include `p16f88.inc`).
- **Program memory** size in instruction words.
- **General-purpose RAM** range in bank 0 (`gprStart`..`gprEnd`).
- **Ports** and their pin counts; the TRIS register name (midrange) or the TRIS-instruction target (baseline).
- **Peripherals** present (timers, USART, ADC, comparator, CCP, EEPROM, …).
- **Oscillator fuse tokens** and the remaining default config tokens.

## 2. Register it

Add an entry alongside the others. Example (a midrange part with ADC and USART):

```java
ChipDefinition.Builder p16f88 = midrange("PIC16F88").program(4096)
        .gpr(0x20, 0x7F)
        .port(portA(8)).port(portB(8))
        .add(TIMER1, TIMER2, CCP, COMPARATOR, EEPROM)
        .adc(7, 10)                 // 7 channels, 10-bit
        .usart("TXREG", "RCREG");
p16f88.osc("XT", "_XT_OSC").osc("HS", "_HS_OSC").osc("LP", "_LP_OSC")
      .osc("INTRC", "_INTRC_IO").osc("EXTRC", "_EXTRC_IO");
put(p16f88.baseConfig("_WDT_OFF & _PWRTE_ON & _MCLR_ON & _BOREN_OFF & _LVP_OFF & _CP_OFF").build());
```

Helper builders `midrange(name)` / `baseline(name)` set sensible defaults (stack depth, TMR0, and
interrupts for midrange). Port helpers `portA(pins)`…`portE(pins)`, `gpio(fileAddr, pins)` (baseline GPIO
via the TRIS instruction) and `gpioIo(pins)` (midrange GPIO with `TRISIO`) cover the common cases.

## 3. Verify

```bash
mvn package
java -jar target/basic2asm.jar --list-chips | grep PIC16F88
java -jar target/basic2asm.jar --chip PIC16F88 --input examples/blink.bas -o /tmp/out.asm
```

Assemble the result with MPASM/gpasm to confirm the fuse tokens and register names match the include file.

## 4. Peripherals that need code

If the new part uses a peripheral whose code generation differs from what exists (most commonly **ADC**),
extend the relevant section of [`CodeGenerator`](../src/main/java/org/basic2asm/codegen/CodeGenerator.java):

- ADC: add a branch to `adcStyle()` and `genAdcRead(...)`/`emitAdcInitPrologue()`.
- Banking / analog disable is handled generically via `ifdef` guards in `emitDigitalIoPrologue()`.

Please add a short example under `examples/` and a test in `TranspilerTest` for new functionality.
