package com.example.funerariamc.service;

import com.example.funerariamc.utils.enums.MovementType;

/** Datos del diálogo "Movimiento": entrada o salida de unidades de un artículo. */
public record InventoryMovementData(Long itemId, MovementType type, Integer quantity, String reason) {
}
