package com.example.funerariamc.service;

import com.example.funerariamc.model.enums.DeathType;
import com.example.funerariamc.model.enums.ExternalCause;

import java.time.LocalDate;

/**
 * Datos del formulario "Registrar difunto" (RF-DIF-01, RF-DIF-02 y HU-56). Para un NN,
 * unidentified es true, el nombre es "NN", lastname lleva una descripción corta ("hombre adulto")
 * y no hay documento. planId es null cuando el servicio es particular.
 */
public record DeceasedData(boolean unidentified, String name, String lastname, String documentNumber,
                           LocalDate birthDate, LocalDate deathDate, DeathType deathType,
                           ExternalCause externalCause, Long clientId, Long planId) {
}
