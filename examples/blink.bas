' ---------------------------------------------------------------
' blink.bas - Flash an LED connected to RB0 on a PIC16F84A.
' Build:
'   java -jar basic2asm.jar --chip PIC16F84A --input examples/blink.bas \
'        --output examples/blink.asm --clock 4MHz
' ---------------------------------------------------------------

OUTPUT RB0          ' make RB0 a digital output

blink:
  HIGH RB0          ' LED on
  DELAY 500         ' wait 500 ms
  LOW RB0           ' LED off
  DELAY 500
  GOTO blink
