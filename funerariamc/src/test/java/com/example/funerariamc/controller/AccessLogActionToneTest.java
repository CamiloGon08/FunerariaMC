package com.example.funerariamc.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Color de la píldora de la columna Acción en la bitácora (paleta verde oscura). */
class AccessLogActionToneTest {

    @Test
    void deniedAccessIsAlert() {
        assertEquals("alert", AccessLogController.tone("ACCESS_DENIED: FINANZAS"));
    }

    @Test
    void failedLoginIsWarning() {
        assertEquals("warn", AccessLogController.tone("LOGIN_FAILED: wrong password"));
    }

    @Test
    void otherActionsAndNullAreNormal() {
        assertEquals("normal", AccessLogController.tone("LOGIN_SUCCESS"));
        assertEquals("normal", AccessLogController.tone("OPEN_MODULE: CLIENTES"));
        assertEquals("normal", AccessLogController.tone(null));
    }
}
