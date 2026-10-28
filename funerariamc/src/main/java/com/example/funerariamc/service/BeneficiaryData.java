package com.example.funerariamc.service;

import com.example.funerariamc.model.enums.Relationship;

/**
 * Datos del diálogo "Agregar beneficiario" (RF-PLA-02). acceptSurcharge indica que el vendedor
 * ya vio y aceptó el recargo que corresponde (RN-09, RN-10).
 */
public record BeneficiaryData(Long planId, String name, String lastname, String documentNumber,
                              Relationship relationship, boolean acceptSurcharge) {
}
