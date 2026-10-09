package com.example.funerariamc.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BusinessExceptionsTest {

    @Test
    void resourceNotFoundIncludesEntityAndIdentifier() {
        assertBusinessMessage(new ResourceNotFoundException("Cliente", 42L),
                "Cliente con id 42 no existe.");
    }

    @Test
    void waitingPeriodExposesRemainingDaysForRn08() {
        WaitingPeriodException exception = new WaitingPeriodException(32L);
        assertAll(
                () -> assertBusinessMessage(exception,
                        "El plan está en período de carencia. Días restantes: 32"),
                () -> assertEquals(32L, exception.getDaysRemaining())
        );
    }

    @Test
    void cremationRequiresAuthorizationForRn03() {
        assertBusinessMessage(new CremationNotAuthorizedException(),
                "Cremación no autorizada: pendiente autorización de la Fiscalía");
    }

    @Test
    void beneficiaryLimitExposesConfiguredMaximumForRn10() {
        BeneficiaryLimitExceededException exception = new BeneficiaryLimitExceededException(7L);
        assertAll(
                () -> assertBusinessMessage(exception,
                        "Se ha superado el límite de beneficiarios permitido: 7"),
                () -> assertEquals(7L, exception.getMaximumBeneficiaries())
        );
    }

    @Test
    void insufficientStockExposesAvailableQuantityForRn24() {
        InsufficientStockException exception = new InsufficientStockException(2L);
        assertAll(
                () -> assertBusinessMessage(exception,
                        "Existencias insuficientes. Cantidad disponible: 2"),
                () -> assertEquals(2L, exception.getAvailableQuantity())
        );
    }

    @Test
    void insufficientStockCanReportZeroAvailability() {
        InsufficientStockException exception = new InsufficientStockException(0L);
        assertAll(
                () -> assertBusinessMessage(exception,
                        "Existencias insuficientes. Cantidad disponible: 0"),
                () -> assertEquals(0L, exception.getAvailableQuantity())
        );
    }

    @Test
    void inactiveEmployeeCannotPerformOperationForRn19() {
        assertBusinessMessage(new InactiveEmployeeException(),
                "El empleado está inactivo y no puede realizar esta operación.");
    }

    @Test
    void unauthorizedActionUsesGenericMessageForRn21AndRn22() {
        assertBusinessMessage(new UnauthorizedActionException(),
                "No tiene permiso para acceder a este módulo");
    }

    private static void assertBusinessMessage(RuntimeException exception, String message) {
        assertAll(
                () -> assertInstanceOf(BusinessException.class, exception),
                () -> assertEquals(message, exception.getMessage())
        );
    }
}
