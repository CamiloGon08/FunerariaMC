package com.example.funerariamc.service;

import java.time.LocalDateTime;

/** Datos del diálogo "Asignar a contrato" de Salas: sala, contrato, ingreso y salida. */
public record AssignWakeData(Long roomId, Long contractId, LocalDateTime start, LocalDateTime end) {
}
