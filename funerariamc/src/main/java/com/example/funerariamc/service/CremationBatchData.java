package com.example.funerariamc.service;

import java.time.LocalDateTime;

/** Datos para programar un lote de cremación (RF-CRE-01): horno o proveedor y fecha y hora. */
public record CremationBatchData(String furnaceCode, LocalDateTime scheduledAt) {
}
