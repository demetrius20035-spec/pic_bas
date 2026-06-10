' ---------------------------------------------------------------
' uart_hello.bas - Print a greeting and a counter over software UART.
' Default software-UART TX pin is RB2 (override with --uart-tx).
' Build:
'   java -jar basic2asm.jar --chip PIC16F628A --input examples/uart_hello.bas \
'        --output examples/uart_hello.asm --clock 4MHz --uart-baud 9600
' ---------------------------------------------------------------
DIM count AS BYTE

UARTINIT
count = 0

send_loop:
  PRINT "Hello from PIC! count="; count
  count = count + 1
  DELAY 1000
  GOTO send_loop
