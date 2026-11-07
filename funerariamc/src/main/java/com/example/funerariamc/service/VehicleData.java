package com.example.funerariamc.service;

import com.example.funerariamc.model.enums.VehicleType;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Datos del formulario "Nuevo vehículo" / "Editar vehículo".
 * Los archivos son la ruta local elegida en el diálogo; al editar pueden ir en null para conservar
 * los que ya están guardados.
 *
 * @param plate                 placa, única
 * @param type                  coche fúnebre o bus
 * @param capacity              puestos (ataúdes en el coche fúnebre, pasajeros en el bus)
 * @param soatExpiry            vencimiento del SOAT
 * @param soatFile              archivo del SOAT (solo se guarda su URL)
 * @param technicalReviewExpiry vencimiento de la revisión técnico-mecánica
 * @param technicalReviewFile   archivo de la revisión técnico-mecánica
 */
public record VehicleData(String plate, VehicleType type, Integer capacity,
                          LocalDate soatExpiry, Path soatFile,
                          LocalDate technicalReviewExpiry, Path technicalReviewFile) {
}
