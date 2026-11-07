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
    void translatesClientActionsKeepingTheDocument() {
        assertEquals("Registro del cliente con documento 1065832417",
                AccessLogLabels.of("CLIENT_CREATED: 1065832417"));
        assertEquals("Actualización del cliente con documento 8001972684",
                AccessLogLabels.of("CLIENT_UPDATED: 8001972684"));
    }

    @Test
    void translatesWakeRoomActions() {
        assertEquals("Registro de la sala de velación Sala 03", AccessLogLabels.of("WAKE_ROOM_REGISTERED: Sala 03"));
        assertEquals("Sala de velación asignada al contrato CT-2026-0001 Sala 01",
                AccessLogLabels.of("WAKE_ROOM_ASSIGNED: CT-2026-0001 Sala 01"));
        assertEquals("Cambio de sala de velación del contrato CT-2026-0001 Sala 01 > Sala 02",
                AccessLogLabels.of("WAKE_ROOM_CHANGED: CT-2026-0001 Sala 01 > Sala 02"));
    }

    @Test
    void translatesSaleActions() {
        assertEquals("Registro del contrato CT-2026-0001 · contrato con plan",
                AccessLogLabels.of("CONTRACT_REGISTERED: CT-2026-0001 PLAN"));
        assertEquals("Registro de la venta N.° 0007 · venta de productos",
                AccessLogLabels.of("SALE_REGISTERED: 0007 PRODUCT"));
        assertEquals("Abono a la venta N.° 0007 · $ 500.000",
                AccessLogLabels.of("SALE_PAYMENT_REGISTERED: 0007 500000.00"));
        assertEquals("Anulación de la venta N.° 0007", AccessLogLabels.of("SALE_VOIDED: 0007"));
        assertEquals("Preparación del cuerpo registrada en el contrato CT-2026-0001",
                AccessLogLabels.of("PREPARATION_REGISTERED: CT-2026-0001"));
    }

    @Test
    void keepsUnknownActionsAsStored() {
        assertEquals("SOMETHING_NEW", AccessLogLabels.of("SOMETHING_NEW"));
        assertEquals("Ingreso a MODULO_X", AccessLogLabels.of("OPEN_MODULE: MODULO_X"));
        assertEquals("", AccessLogLabels.of(null));
    }
}
