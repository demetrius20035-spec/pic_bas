' ---------------------------------------------------------------
' adc_read.bas - Read analog channel 0 and report it over UART.
' Build for a chip with an ADC, e.g. PIC16F877A:
'   java -jar basic2asm.jar --chip PIC16F877A --input examples/adc_read.bas \
'        --output examples/adc_read.asm --clock 20MHz --uart-mode hardware
' ---------------------------------------------------------------
DIM sample AS BYTE

UARTINIT

read_loop:
  sample = ADC(0)
  PRINT "ADC0="; sample
  DELAY 250
  GOTO read_loop
