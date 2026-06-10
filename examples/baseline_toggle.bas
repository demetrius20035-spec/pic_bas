' ---------------------------------------------------------------
' baseline_toggle.bas - Baseline PIC12F509 (12-bit core) GP0 toggle.
' Build:
'   java -jar basic2asm.jar --chip PIC12F509 --input examples/baseline_toggle.bas \
'        --output examples/baseline_toggle.asm --clock 4MHz
' ---------------------------------------------------------------
OUTPUT GP0

toggle_loop:
  TOGGLE GP0
  DELAY 250
  GOTO toggle_loop
