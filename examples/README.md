# Examples

Each `.bas` file here has a matching generated `.asm` (and the build command in its header comment).
Regenerate any of them with `basic2asm`; assemble the `.asm` with MPASM or gpasm.

| Example | Chip | Demonstrates | Build |
| --- | --- | --- | --- |
| `blink.bas` | PIC16F84A | digital output, `DELAY`, `GOTO` | `--chip PIC16F84A --clock 4MHz --map` |
| `uart_hello.bas` | PIC16F628A | software UART, `PRINT`, counter | `--chip PIC16F628A --clock 4MHz --uart-mode software` |
| `fade_count.bas` | PIC16F84A | `FOR`, `IF/ELSE`, a `FUNCTION` | `--chip PIC16F84A --clock 4MHz` |
| `adc_read.bas` | PIC16F877A | `ADC`, hardware USART `PRINT` | `--chip PIC16F877A --clock 20MHz --uart-mode hardware` |
| `interrupt_counter.bas` | PIC16F84A | `SUB ISR`, `POKE`, TMR0 interrupt | `--chip PIC16F84A --clock 4MHz` |
| `baseline_toggle.bas` | PIC12F509 | baseline core, `TRIS` instruction | `--chip PIC12F509 --clock 4MHz` |
| `config.json` | — | a JSON configuration file | `--config examples/config.json` |

Example invocation:

```bash
java -jar ../target/basic2asm.jar --chip PIC16F84A --input blink.bas \
     --output blink.asm --clock 4MHz --map
```
