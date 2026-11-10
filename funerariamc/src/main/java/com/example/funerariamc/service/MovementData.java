package com.example.funerariamc.service;

import com.example.funerariamc.model.enums.TransactionType;

import java.math.BigDecimal;

/**
 * Datos del formulario "Registrar movimiento". saleId es opcional (solo para ingresos de una venta) y
 * accountId también (solo para egresos: la cuenta a la que se carga el gasto).
 */
public record MovementData(TransactionType type, BigDecimal amount, String description, Long saleId,
                           Long accountId) {
}
