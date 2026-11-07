package com.example.funerariamc.service;

import java.math.BigDecimal;

/** Abono a una venta a crédito. La descripción es opcional. */
public record SalePaymentData(Long saleId, BigDecimal amount, String description) {
}
