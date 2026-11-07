package com.example.funerariamc.service;

import com.example.funerariamc.model.enums.StopType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Datos del diálogo "Asignar traslado": un contrato, una hora de salida, los vehículos
 * con su conductor y las paradas del recorrido. Las paradas se copian a cada vehículo del traslado
 * (el bus acompaña al coche fúnebre).
 *
 * @param contractId  contrato de servicio vigente
 * @param scheduledAt fecha y hora de salida
 * @param vehicles    coche fúnebre y bus incluidos, y buses adicionales
 * @param stops       paradas en orden: salida, recogida, ceremonia y destino
 * @param notes       observaciones (opcional)
 */
public record TransferData(Long contractId, LocalDateTime scheduledAt, List<Assignment> vehicles,
                           List<StopData> stops, String notes) {

    /** Un vehículo con su conductor. included = true: sin costo; false: bus adicional. */
    public record Assignment(Long vehicleId, Long driverId, boolean included) {
    }

    /** Una parada del recorrido con su hora prevista. */
    public record StopData(StopType type, String placeName, String address, LocalDateTime plannedAt) {
    }
}
