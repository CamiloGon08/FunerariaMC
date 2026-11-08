package com.example.funerariamc.service;

import com.example.funerariamc.model.enums.InventoryCategory;

import java.math.BigDecimal;
import java.nio.file.Path;

/**
 * Datos del formulario "Nuevo artículo" / "Editar artículo".
 * initialStock solo se usa al registrar; al editar, el stock cambia únicamente con movimientos.
 * photo es la imagen elegida (JPG o PNG); null deja la foto como está.
 */
public record InventoryItemData(String name, InventoryCategory category, Integer initialStock,
                                Integer minimumStock, BigDecimal unitPrice, Path photo) {
}
