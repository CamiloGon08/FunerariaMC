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
    void translatesInventoryActions() {
        assertEquals("Registro del artículo de inventario IN-0012",
                AccessLogLabels.of("INVENTORY_ITEM_REGISTERED: IN-0012"));
        assertEquals("Actualización del artículo de inventario IN-0012",
                AccessLogLabels.of("INVENTORY_ITEM_UPDATED: IN-0012"));
        assertEquals("Entrada de 5 unidades del artículo IN-0012",
                AccessLogLabels.of("INVENTORY_MOVEMENT_REGISTERED: IN-0012 ENTRY 5"));
        assertEquals("Salida de 1 unidad del artículo IN-0012",
                AccessLogLabels.of("INVENTORY_MOVEMENT_REGISTERED: IN-0012 EXIT 1"));
    }

    @Test
    void translatesCremationActions() {
        assertEquals("Lote de cremación programado L-0033 BAQ-01",
                AccessLogLabels.of("CREMATION_BATCH_SCHEDULED: L-0033 BAQ-01"));
        assertEquals("Lote de cremación reprogramado L-0033 BAQ-01 2026-11-07 08:00",
                AccessLogLabels.of("CREMATION_BATCH_RESCHEDULED: L-0033 BAQ-01 2026-11-07 08:00"));
        assertEquals("Difunto agregado al lote de cremación L-0033 DF-0042",
                AccessLogLabels.of("CREMATION_ASSIGNED: L-0033 DF-0042"));
        assertEquals("Difunto quitado del lote de cremación L-0033 DF-0042",
                AccessLogLabels.of("CREMATION_REMOVED: L-0033 DF-0042"));
        assertEquals("Lote de cremación entregado al horno L-0033",
                AccessLogLabels.of("CREMATION_BATCH_STARTED: L-0033"));
        assertEquals("Certificado de cremación registrado en el lote L-0033 DF-0042 CC-1001",
                AccessLogLabels.of("CREMATION_REGISTERED: L-0033 DF-0042 CC-1001"));
        assertEquals("Compra de cremaciones confirmada CP-0003",
                AccessLogLabels.of("CREMATION_PURCHASE_CONFIRMED: CP-0003"));
        assertEquals("Compra de cremaciones solicitada CP-0003 BAQ-CREMA1 x10",
                AccessLogLabels.of("CREMATION_PURCHASE_REQUESTED: CP-0003 BAQ-CREMA1 x10"));
        assertEquals("Ingreso a Cremaciones", AccessLogLabels.of("OPEN_MODULE: CREMACIONES"));
    }

    @Test
    void translatesAlliedNetworkActions() {
        assertEquals("Registro de la funeraria aliada con NIT 900214556-3",
                AccessLogLabels.of("ALLIED_HOME_REGISTERED: 900214556-3"));
        assertEquals("Desactivación del convenio con la funeraria aliada con NIT 900214556-3",
                AccessLogLabels.of("ALLIED_HOME_DEACTIVATED: 900214556-3"));
        assertEquals("Pago pendiente registrado a la funeraria aliada con NIT 800991447-1 · contrato CT-2026-0402 · $ 3.200.000",
                AccessLogLabels.of("ALLIED_PAYMENT_REGISTERED: 800991447-1 CT-2026-0402 3200000.00"));
        assertEquals("Pago a la funeraria aliada con NIT 800991447-1 · pagado, egreso MV-0045 · $ 3.200.000",
                AccessLogLabels.of("ALLIED_PAYMENT_PAID: 800991447-1 MV-0045 3200000.00"));
        assertEquals("Pago anulado a la funeraria aliada con NIT 800991447-1 · $ 3.200.000",
                AccessLogLabels.of("ALLIED_PAYMENT_VOIDED: 800991447-1 3200000.00"));
        assertEquals("Ingreso a Red de funerarias aliadas", AccessLogLabels.of("OPEN_MODULE: RED_ALIADAS"));
    }

    @Test
    void translatesFinanceActions() {
        assertEquals("Registro del movimiento MV-0012 · ingreso · $ 150.000",
                AccessLogLabels.of("FINANCIAL_TRANSACTION_REGISTERED: MV-0012 INCOME 150000.00"));
        assertEquals("Registro del movimiento MV-0013 · egreso · $ 80.000",
                AccessLogLabels.of("FINANCIAL_TRANSACTION_REGISTERED: MV-0013 EXPENSE 80000.00"));
        assertEquals("Anulación del movimiento MV-0012", AccessLogLabels.of("FINANCIAL_TRANSACTION_VOIDED: MV-0012"));
        assertEquals("Comisión de 2026-10 del empleado con documento 1065000111 · $ 250.000",
                AccessLogLabels.of("COMMISSION_REGISTERED: 2026-10 1065000111 250000.00"));
        assertEquals("Exportación del resumen financiero en Excel del 2026-10-01 al 2026-10-31",
                AccessLogLabels.of("FINANCIAL_REPORT_EXPORTED: EXCEL 2026-10-01 2026-10-31"));
        assertEquals("Ingreso a Finanzas", AccessLogLabels.of("OPEN_MODULE: FINANZAS"));
    }

    @Test
    void translatesAnalyticsExport() {
        assertEquals("Exportación del panel de análisis del 2026-10-01 al 2026-10-31",
                AccessLogLabels.of("ANALYTICS_REPORT_EXPORTED: 2026-10-01 2026-10-31"));
        assertEquals("Ingreso a Panel de análisis", AccessLogLabels.of("OPEN_MODULE: ANALISIS"));
    }

    @Test
    void translatesParameterActions() {
        assertEquals("Cambio del parámetro Período de carencia: de 62 a 45",
                AccessLogLabels.of("SYSTEM_PARAMETER_UPDATED: PLAN_WAITING_DAYS 62 -> 45"));
        assertEquals("Cambio del parámetro Tarifa por bus adicional: 80000",
                AccessLogLabels.of("SYSTEM_PARAMETER_UPDATED: EXTRA_BUS_FEE - -> 80000"));
        assertEquals("Creación del parámetro TARIFA_PRUEBA: 10",
                AccessLogLabels.of("SYSTEM_PARAMETER_CREATED: TARIFA_PRUEBA - -> 10"));
        assertEquals("Cambio del parámetro Período de carencia",
                AccessLogLabels.of("SYSTEM_PARAMETER_UPDATED: PLAN_WAITING_DAYS"));
        assertEquals("Ingreso a Parámetros del sistema", AccessLogLabels.of("OPEN_MODULE: PARAMETROS"));
    }

    @Test
    void keepsUnknownActionsAsStored() {
        assertEquals("SOMETHING_NEW", AccessLogLabels.of("SOMETHING_NEW"));
        assertEquals("Ingreso a MODULO_X", AccessLogLabels.of("OPEN_MODULE: MODULO_X"));
        assertEquals("", AccessLogLabels.of(null));
    }
}
