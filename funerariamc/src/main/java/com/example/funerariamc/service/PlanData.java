package com.example.funerariamc.service;

import java.time.LocalDate;

/**
 * Datos del formulario "Afiliar plan". La cuota mensual no viene del formulario: sale del valor de
 * cada tipo de plan en Parámetros, y solo cambia si se cambia allí.
 */
public record PlanData(Long clientId, String planName, LocalDate startDate) {
}
