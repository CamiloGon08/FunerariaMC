package com.example.funerariamc.service;

import java.math.BigDecimal;

/** Datos de una solicitud de compra de cremaciones prepagadas: proveedor, cantidad y precio unitario. */
public record CremationPurchaseData(Long providerId, Integer quantity, BigDecimal unitPrice) {
}
