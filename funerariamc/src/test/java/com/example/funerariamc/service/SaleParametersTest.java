package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SaleParametersTest {

    private final SystemParameterService configuration = mock(SystemParameterService.class);
    private final SaleParameters parameters = new SaleParameters(configuration);

    private record ParameterCase(String key, Function<SaleParameters, BigDecimal> read) {
    }

    private final List<ParameterCase> cases = List.of(
            new ParameterCase("WAKE_ROOM_SURCHARGE", value -> value.wakeRoomSurcharge()),
            new ParameterCase("EXTRA_BUS_FEE", value -> value.extraBusFee()),
            new ParameterCase("PLAN_BASIC_COFFIN_PRICE", value -> value.basicCoffinPrice()),
            new ParameterCase("MINIMUM_WAGE", value -> value.minimumWage())
    );

    @Test
    void readsTheAgreedKeysWithoutChangingTheirMonetaryPrecision() {
        for (int index = 0; index < cases.size(); index++) {
            ParameterCase value = cases.get(index);
            BigDecimal configured = new BigDecimal(index + ".25");
            when(configuration.getBigDecimal(value.key())).thenReturn(configured);
            assertEquals(configured, value.read().apply(parameters));
            verify(configuration).getBigDecimal(value.key());
        }
    }

    @Test
    void zeroValuesAreReadFromConfigurationInsteadOfReplacedWithAnInventedFee() {
        when(configuration.getBigDecimal(anyString())).thenReturn(BigDecimal.ZERO);
        for (ParameterCase value : cases) {
            assertEquals(BigDecimal.ZERO, value.read().apply(parameters));
        }
    }

    @Test
    void negativeValuesAreRejectedWithTheParameterThatMustBeCorrected() {
        when(configuration.getBigDecimal(anyString())).thenReturn(new BigDecimal("-0.01"));
        for (ParameterCase value : cases) {
            InvalidDataException failure = assertThrows(InvalidDataException.class,
                    () -> value.read().apply(parameters));
            assertTrue(failure.getMessage().contains(value.key()));
        }
    }

    @Test
    void missingConfigurationNeverEnablesFallbackBusinessValues() {
        when(configuration.getBigDecimal(anyString()))
                .thenThrow(new InvalidDataException("Falta un parámetro"));
        for (ParameterCase value : cases) {
            InvalidDataException failure = assertThrows(InvalidDataException.class,
                    () -> value.read().apply(parameters));
            assertTrue(failure.getMessage().contains(value.key()));
            assertTrue(failure.getMessage().contains("Parámetros del sistema"));
        }
    }

    @Test
    void malformedConfigurationDoesNotPretendThatTheExistingParameterIsMissing() {
        when(configuration.getBigDecimal("MINIMUM_WAGE"))
                .thenThrow(new InvalidDataException("El parámetro no contiene un decimal válido"));
        InvalidDataException failure = assertThrows(InvalidDataException.class,
                () -> parameters.minimumWage());
        assertTrue(failure.getMessage().contains("MINIMUM_WAGE"));
        assertFalse(failure.getMessage().contains("Falta"));
    }
}
