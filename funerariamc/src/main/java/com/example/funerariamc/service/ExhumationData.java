package com.example.funerariamc.service;

import com.example.funerariamc.model.enums.ExhumationDestination;
import com.example.funerariamc.model.enums.ExhumationReason;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Datos del diálogo "Registrar exhumación" (RF-BOV-05). authorizationFile es el archivo que eligió
 * el usuario; el servicio lo copia con FileStorageService y guarda solo su URL (RN-25).
 */
public record ExhumationData(Long vaultId, LocalDate exhumationDate, ExhumationReason reason,
                             ExhumationDestination destination, Path authorizationFile, String notes) {
}
