package com.example.funerariamc.service;

import java.time.LocalDateTime;

/** Datos del certificado de cremación (RF-CRE-02 paso 4): número, fecha y hora en que terminó. */
public record CremationCertificateData(Long cremationId, String certificateNumber, LocalDateTime completedAt) {
}
