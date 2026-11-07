package com.example.funerariamc.service;

import java.time.LocalDateTime;

/**
 * Datos para agregar un difunto a un lote (RF-CRE-02): el lote, el difunto y el tanatólogo que
 * preparó el cuerpo. preparationCompletedAt solo se usa si el contrato activo del difunto todavía
 * no tiene la preparación registrada (RN-17); en los demás casos puede ir en null.
 */
public record AssignCremationData(Long batchId, Long deceasedId, Long thanatopractorId,
                                  LocalDateTime preparationCompletedAt) {
}
