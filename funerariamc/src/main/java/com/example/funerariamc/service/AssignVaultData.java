package com.example.funerariamc.service;

import java.time.LocalDate;

/** Datos del diálogo "Asignar bóveda" (RF-BOV-02): bóveda, titular del arriendo, difunto e inicio. */
public record AssignVaultData(Long vaultId, Long clientId, Long deceasedId, LocalDate leaseStartDate) {
}
