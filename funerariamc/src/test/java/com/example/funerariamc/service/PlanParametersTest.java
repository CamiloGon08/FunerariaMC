package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanParametersTest {

    private final SystemParameterService configuration = mock(SystemParameterService.class);
    private final PlanParameters parameters = new PlanParameters(configuration);

    @Test
    void planValuesComeFromTheAgreedParameterKeys() {
        when(configuration.getLong(PlanParameters.WAITING_DAYS)).thenReturn(31L);
        when(configuration.getLong(PlanParameters.BENEFICIARY_LIMIT)).thenReturn(5L);
        when(configuration.getBigDecimal(PlanParameters.NON_DIRECT_SURCHARGE)).thenReturn(new BigDecimal("50000.25"));
        when(configuration.getBigDecimal(PlanParameters.EXTRA_BENEFICIARY_SURCHARGE)).thenReturn(BigDecimal.ZERO);

        assertEquals(31, parameters.waitingDays());
        assertEquals(5, parameters.beneficiaryLimit());
        assertEquals(new BigDecimal("50000.25"), parameters.nonDirectSurcharge());
        assertEquals(BigDecimal.ZERO, parameters.extraBeneficiarySurcharge());
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 2147483648L, Long.MAX_VALUE})
    void rejectsInvalidIntegerRangesInsteadOfTruncating(long value) {
        when(configuration.getLong(anyString())).thenReturn(value);
        assertThrows(InvalidDataException.class, parameters::waitingDays);
        assertThrows(InvalidDataException.class, parameters::beneficiaryLimit);
    }

    @Test
    void missingConfigurationDoesNotEnableFallbackWaitingDays() {
        InvalidDataException error = new InvalidDataException("Falta configuración");
        when(configuration.getLong(PlanParameters.WAITING_DAYS)).thenThrow(error);
        assertSame(error, assertThrows(InvalidDataException.class, parameters::waitingDays));
    }

    @Test
    void negativeSurchargesAreRejected() {
        when(configuration.getBigDecimal(anyString())).thenReturn(new BigDecimal("-1"));
        assertThrows(InvalidDataException.class, parameters::nonDirectSurcharge);
        assertThrows(InvalidDataException.class, parameters::extraBeneficiarySurcharge);
    }
}
