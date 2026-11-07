package com.example.funerariamc.service;

/**
 * Datos de un proveedor de cremaciones. id null crea uno nuevo; el código solo se escribe al
 * crearlo porque los lotes guardan ese código.
 */
public record CremationProviderData(Long id, String code, String name, Integer minimumAvailable, boolean active) {
}
