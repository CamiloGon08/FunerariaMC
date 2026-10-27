package com.example.funerariamc.service;

import java.math.BigDecimal;
import java.time.YearMonth;

/** Datos del diálogo "Registrar pago": el mes que cubre la cuota y el valor recibido. */
public record PlanPaymentData(Long planId, YearMonth period, BigDecimal amount) {
}
