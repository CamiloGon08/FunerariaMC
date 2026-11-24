package com.example.funerariamc.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** La edad debe corresponder al documento: cédula desde los 18 años y tarjeta de identidad de 7 a 17. */
class DocumentAgeTest {

    private static final LocalDate TODAY = LocalDate.now(AccessLogService.APP_ZONE);

    @Test
    void cedulaNeedsEighteenYears() {
        assertNull(ClientService.ageProblem("CC", TODAY.minusYears(18)));
        assertNotNull(ClientService.ageProblem("CC", TODAY.minusYears(18).plusDays(1)));
        assertNotNull(ClientService.ageProblem("CC", TODAY.minusYears(10)));
    }

    @Test
    void identityCardIsFromSevenToSeventeen() {
        assertNull(ClientService.ageProblem("TI", TODAY.minusYears(7)));
        assertNull(ClientService.ageProblem("TI", TODAY.minusYears(18).plusDays(1)));
        assertNotNull(ClientService.ageProblem("TI", TODAY.minusYears(7).plusDays(1)));
        assertNotNull(ClientService.ageProblem("TI", TODAY.minusYears(18)));
    }

    @Test
    void otherDocumentsOrNoBirthDateAreNotChecked() {
        assertNull(ClientService.ageProblem("CE", TODAY.minusYears(5)));
        assertNull(ClientService.ageProblem("PA", TODAY.minusYears(5)));
        assertNull(ClientService.ageProblem("CC", null));
    }
}
