package com.example.funerariamc.service;

import com.example.funerariamc.model.enums.DocumentType;

import java.nio.file.Path;
import java.time.LocalDate;

/** Documento legal de un difunto (RF-DIF-01 y RF-DIF-03): datos del formulario y el archivo elegido. */
public record DeathDocumentData(DocumentType type, String issuingAuthority, String documentNumber,
                                LocalDate issueDate, Path file, String notes) {
}

