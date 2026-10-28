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
        when(configuration.getLong("PLAN_MAX_MONTHS_AHEAD")).thenReturn(4L);
        when(configuration.getLong("PLAN_WAITING_SOON_DAYS")).thenReturn(10L);
        when(configuration.getLong("PLAN_MAX_START_MONTHS_AHEAD")).thenReturn(2L);
        when(configuration.getBigDecimal(PlanParameters.NON_DIRECT_SURCHARGE)).thenReturn(new BigDecimal("50000.25"));
        when(configuration.getBigDecimal(PlanParameters.EXTRA_BENEFICIARY_SURCHARGE)).thenReturn(BigDecimal.ZERO);

        assertEquals(31, parameters.waitingDays());
        assertEquals(5, parameters.beneficiaryLimit());
        assertEquals(4, parameters.maxMonthsAhead());
        assertEquals(10, parameters.waitingSoonDays());
        assertEquals(2, parameters.maxStartMonthsAhead());
        assertEquals(new BigDecimal("50000.25"), parameters.nonDirectSurcharge());
        assertEquals(BigDecimal.ZERO, parameters.extraBeneficiarySurcharge());
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 2147483648L, Long.MAX_VALUE})
    void rejectsInvalidIntegerRangesInsteadOfTruncating(long value) {
        when(configuration.getLong(anyString())).thenReturn(value);
        assertThrows(InvalidDataException.class, parameters::waitingDays);
        assertThrows(InvalidDataException.class, parameters::beneficiaryLimit);
        assertThrows(InvalidDataException.class, parameters::maxMonthsAhead);
        assertThrows(InvalidDataException.class, parameters::waitingSoonDays);
        assertThrows(InvalidDataException.class, parameters::maxStartMonthsAhead);
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

    @Test
    void missingAdvancePaymentLimitDoesNotUseTwelveMonthsAsFallback() {
        InvalidDataException missing = new InvalidDataException("Falta un parámetro");
        when(configuration.getLong("PLAN_MAX_MONTHS_AHEAD")).thenThrow(missing);
        assertSame(missing, assertThrows(InvalidDataException.class, parameters::maxMonthsAhead));
    }

    @Test
    void missingWaitingWarningWindowDoesNotUseThirtyDaysAsFallback() {
        InvalidDataException missing = new InvalidDataException("Falta un parámetro");
        when(configuration.getLong("PLAN_WAITING_SOON_DAYS")).thenThrow(missing);
        assertSame(missing, assertThrows(InvalidDataException.class, parameters::waitingSoonDays));
    }

    @Test
    void zeroDisablesAdvanceLimitsAndAdvanceWaitingWarnings() {
        when(configuration.getLong("PLAN_MAX_MONTHS_AHEAD")).thenReturn(0L);
        when(configuration.getLong("PLAN_WAITING_SOON_DAYS")).thenReturn(0L);
        when(configuration.getLong("PLAN_MAX_START_MONTHS_AHEAD")).thenReturn(0L);
        assertEquals(0, parameters.maxMonthsAhead());
        assertEquals(0, parameters.waitingSoonDays());
        assertEquals(0, parameters.maxStartMonthsAhead());
    }

    @Test
    void missingStartDateLimitDoesNotUseOneMonthAsFallback() {
        InvalidDataException missing = new InvalidDataException("Falta un parámetro");
        when(configuration.getLong("PLAN_MAX_START_MONTHS_AHEAD")).thenThrow(missing);
        assertSame(missing, assertThrows(InvalidDataException.class, parameters::maxStartMonthsAhead));
    }
}
