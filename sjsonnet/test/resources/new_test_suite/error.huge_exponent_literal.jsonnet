// A numeric literal with an exponent BigDecimal can't represent must be a clean parse error,
// not an uncaught java.lang.NumberFormatException.
1e99999999999999999999
