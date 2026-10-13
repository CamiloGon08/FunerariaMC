package com.example.funerariamc.exception;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InstallmentExceedsBalanceExceptionTest {

    @Test
    void exceptionIsUncheckedAndReportsOutstandingBalanceForRfCiv07() {
        BigDecimal balance = new BigDecimal("150000.00");
        InstallmentExceedsBalanceException exception =
                new InstallmentExceedsBalanceException(balance);

        assertAll(
                () -> assertInstanceOf(BusinessException.class, exception),
                () -> assertInstanceOf(RuntimeException.class, exception),
                () -> assertEquals(balance, exception.getOutstandingBalance()),
                () -> assertEquals("El abono supera el saldo pendiente: 150000.00",
                        exception.getMessage())
        );
    }

    @Test
    void exceptionPreservesCentsWithoutRounding() {
        BigDecimal balance = new BigDecimal("150000.37");
        InstallmentExceedsBalanceException exception =
                new InstallmentExceedsBalanceException(balance);

        assertAll(
                () -> assertEquals(balance, exception.getOutstandingBalance()),
                () -> assertEquals("El abono supera el saldo pendiente: 150000.37",
                        exception.getMessage())
        );
    }

    @Test
    void exceptionReportsZeroBalanceForSettledSale() {
        InstallmentExceedsBalanceException exception =
                new InstallmentExceedsBalanceException(BigDecimal.ZERO);

        assertAll(
                () -> assertEquals(BigDecimal.ZERO, exception.getOutstandingBalance()),
                () -> assertEquals("El abono supera el saldo pendiente: 0",
                        exception.getMessage())
        );
    }

    @Test
    void messageDoesNotUseScientificNotation() {
        InstallmentExceedsBalanceException exception =
                new InstallmentExceedsBalanceException(new BigDecimal("1E+6"));

        assertEquals("El abono supera el saldo pendiente: 1000000", exception.getMessage());
    }

    @Test
    void constructorRequiresOutstandingBalance() {
        assertThrows(NullPointerException.class,
                () -> new InstallmentExceedsBalanceException(null));
    }
}
