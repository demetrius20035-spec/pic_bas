' ---------------------------------------------------------------
' fade_count.bas - FOR loop, arithmetic and a user SUB/FUNCTION.
' ---------------------------------------------------------------
DIM i AS BYTE
DIM result AS BYTE

OUTPUT RB0

FOR i = 1 TO 10
  result = square(i)
  IF result > 50 THEN
    HIGH RB0
  ELSE
    LOW RB0
  ENDIF
  DELAY 100
NEXT i

END

FUNCTION square(x)
  RETURN x * x
END FUNCTION
