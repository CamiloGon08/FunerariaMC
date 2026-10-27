package com.example.funerariamc.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Datos del formulario "Afiliar plan" (RF-PLA-01). */
public record PlanData(Long clientId, String planName, BigDecimal monthlyFee, LocalDate startDate) {
}
