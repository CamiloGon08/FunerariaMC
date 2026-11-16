package com.example.funerariamc.service;

/** Se publica al afiliar un plan; el correo de bienvenida al titular sale después de guardar. */
public record PlanRegisteredEvent(Long planId) {
}
