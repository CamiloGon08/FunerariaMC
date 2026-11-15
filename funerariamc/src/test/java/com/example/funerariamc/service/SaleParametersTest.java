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
            new ParameterCase("MINIMUM_WAGE", value -> value.minimumWage()),
            new ParameterCase("PLAN_PREMIUM_COFFIN_PRICE", value -> value.premiumCoffinPrice()),
            new ParameterCase("INTERCITY_TRANSFER_PRICE_PER_KM", value -> value.intercityPricePerKm()),
            new ParameterCase("VAULT_LEASE_PRICE_PER_YEAR", value -> value.vaultLeasePerYear()),
            new ParameterCase("DOUBLE_LOT_PRICE", value -> value.doubleLotPrice()),
            new ParameterCase("LOT_CONSTRUCTION_PRICE", value -> value.lotConstructionPrice()),
            new ParameterCase("OSSUARY_PRICE", value -> value.ossuaryPrice()),
            new ParameterCase("LOT_PREPARATION_FEE", value -> value.lotPreparationFee())
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

    @Test
    void missingOptionalSuggestionDoesNotInventAValueOrReadAMissingParameter() {
        when(configuration.exists(SaleParameters.CREMATION_PREPAID_PRICE)).thenReturn(false);

        assertNull(parameters.suggested(SaleParameters.CREMATION_PREPAID_PRICE));

        verify(configuration, never()).getBigDecimal(anyString());
    }

    @Test
    void optionalSuggestionPreservesTheConfiguredAmountIncludingZero() {
        when(configuration.exists(SaleParameters.NETWORK_FEE_AMOUNT)).thenReturn(true);
        when(configuration.getBigDecimal(SaleParameters.NETWORK_FEE_AMOUNT))
                .thenReturn(new BigDecimal("1250.50"), BigDecimal.ZERO);

        assertEquals(new BigDecimal("1250.50"), parameters.suggested(SaleParameters.NETWORK_FEE_AMOUNT));
        assertEquals(BigDecimal.ZERO, parameters.suggested(SaleParameters.NETWORK_FEE_AMOUNT));
    }

    @Test
    void invalidOptionalSuggestionReturnsNoSuggestionInsteadOfABusinessDefault() {
        when(configuration.exists(SaleParameters.THANATOLOGIST_BONUS_PER_BODY)).thenReturn(true);
        when(configuration.getBigDecimal(SaleParameters.THANATOLOGIST_BONUS_PER_BODY))
                .thenReturn(new BigDecimal("-1"))
                .thenThrow(new InvalidDataException("No contiene un decimal válido"));

        assertNull(parameters.suggested(SaleParameters.THANATOLOGIST_BONUS_PER_BODY));
        assertNull(parameters.suggested(SaleParameters.THANATOLOGIST_BONUS_PER_BODY));
    }
}
