# Contributing to basic2asm

Thanks for your interest in improving basic2asm! This project aims to be a friendly, community-maintained
BASIC-to-PIC transpiler. Contributions of all sizes are welcome.

## Ways to contribute

- **Add a chip** — usually a single entry in `ChipRegistry`. See [docs/ADDING_A_CHIP.md](docs/ADDING_A_CHIP.md).
- **Add/extend a peripheral** — e.g. ADC support for more parts, hardware I2C/SPI, PWM/CCP, EEPROM read/write.
- **Improve code generation** — better delay calibration, 16-bit arithmetic, peephole optimisation,
  `--optimize speed` paths, automatic `pagesel`/page handling.
- **Language features** — `SELECT CASE`, arrays, string handling, more built-ins.
- **Docs, examples and tests.**

## Development

```bash
mvn package      # build the jar
mvn test         # run the test suite
```

The project targets **Java 8** and has **no runtime dependencies** (JUnit is test-only). Please keep it
that way unless there is a strong reason.

### Project layout

See the architecture table in the [README](README.md#architecture). The compiler is a clean pipeline:
`lexer → parser → sema → codegen`, with chips described declaratively in `chip/`.

### Coding guidelines

- Match the surrounding style; keep methods focused and commented where the intent isn't obvious.
- New functionality should come with:
  - a unit/integration test in `src/test/java` (drive the pipeline via `Transpiler.compile`), and
  - ideally an example in `examples/` with its generated `.asm` committed.
- Generated assembly should be valid for **MPASM** (and ideally **gpasm**). When in doubt, assemble it.
- Report errors through `DiagnosticReporter` with a source line/column — never throw raw exceptions to the user.

### Pull requests

1. Fork and create a feature branch.
2. Add tests and run `mvn test` (all green).
3. Describe the change and, for codegen changes, paste a small before/after asm snippet.

By contributing you agree that your contributions are licensed under the project's MIT license.
