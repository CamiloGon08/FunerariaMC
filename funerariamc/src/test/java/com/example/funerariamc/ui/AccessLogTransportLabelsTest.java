package com.example.funerariamc.ui;

import com.example.funerariamc.service.AccessLogService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Textos en español de las acciones de Transporte en la bitácora (archivo aparte para no chocar con otros módulos). */
class AccessLogTransportLabelsTest {

    @Test
    void translatesVehicleActionsKeepingThePlate() {
        assertEquals("Registro del vehículo KLM-519",
                AccessLogLabels.of(AccessLogService.VEHICLE_REGISTERED + "KLM-519"));
        assertEquals("Vehículo enviado a mantenimiento TRX-204",
                AccessLogLabels.of(AccessLogService.VEHICLE_MAINTENANCE + "TRX-204"));
        assertEquals("Vehículo disponible de nuevo TRX-204",
                AccessLogLabels.of(AccessLogService.VEHICLE_AVAILABLE + "TRX-204"));
    }

    @Test
    void translatesTransferActionsKeepingTheContract() {
        assertEquals("Traslado asignado al contrato CT-2026-0001 KLM-519, TRX-204",
                AccessLogLabels.of(AccessLogService.TRANSPORT_ASSIGNED + "CT-2026-0001 KLM-519, TRX-204"));
        assertEquals("Llegada a una parada del traslado del contrato CT-2026-0001 Parroquia San José",
                AccessLogLabels.of(AccessLogService.TRANSPORT_ARRIVED + "CT-2026-0001 Parroquia San José"));
        assertEquals("Traslado cancelado del contrato CT-2026-0001",
                AccessLogLabels.of(AccessLogService.TRANSPORT_CANCELLED + "CT-2026-0001"));
    }
}
