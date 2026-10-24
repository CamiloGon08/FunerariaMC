package com.example.funerariamc.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Textos en español de la columna "Acción" de la bitácora. */
class AccessLogLabelsTest {

    @Test
    void translatesKnownActions() {
        assertEquals("Inicio de sesión", AccessLogLabels.of("LOGIN_SUCCESS"));
        assertEquals("Cierre de sesión", AccessLogLabels.of("LOGOUT"));
        assertEquals("Ingreso a Clientes", AccessLogLabels.of("OPEN_MODULE: CLIENTES"));
        assertEquals("Ingreso a Bitácora de accesos", AccessLogLabels.of("OPEN_MODULE: BITACORA"));
        assertEquals("Acceso denegado a Finanzas", AccessLogLabels.of("ACCESS_DENIED: FINANZAS"));
        assertEquals("Intento de ingreso fallido: contraseña incorrecta",
                AccessLogLabels.of("LOGIN_FAILED: WRONG_PASSWORD"));
        assertEquals("Solicitud de recuperación de contraseña por correo",
                AccessLogLabels.of("PASSWORD_RESET_REQUESTED: EMAIL"));
        assertEquals("Recuperación de contraseña: código incorrecto",
                AccessLogLabels.of("PASSWORD_RESET_FAILED: INVALID_CODE"));
        assertEquals("Contraseña restablecida", AccessLogLabels.of("PASSWORD_RESET_COMPLETED"));
    }

        @Test
    void translatesEmployeeActionsKeepingTheDocument() {
        assertEquals("Registro del empleado con documento 1065234118",
                AccessLogLabels.of("EMPLOYEE_CREATED: 1065234118"));
        assertEquals("Actualización del empleado con documento 1065234118",
                AccessLogLabels.of("EMPLOYEE_UPDATED: 1065234118"));
        assertEquals("Desactivación del empleado con documento 12725893",
                AccessLogLabels.of("EMPLOYEE_DEACTIVATED: 12725893"));
        assertEquals("Activación del empleado con documento 12725893",
                AccessLogLabels.of("EMPLOYEE_ACTIVATED: 12725893"));
    }

    @Test
    void keepsUnknownActionsAsStored() {
        assertEquals("SOMETHING_NEW", AccessLogLabels.of("SOMETHING_NEW"));
        assertEquals("Ingreso a MODULO_X", AccessLogLabels.of("OPEN_MODULE: MODULO_X"));
        assertEquals("", AccessLogLabels.of(null));
    }
}
