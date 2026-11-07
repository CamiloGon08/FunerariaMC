package com.example.funerariamc.service;

import java.math.BigDecimal;

/** Datos del formulario "Nueva sala". special marca la sala con recargo. */
public record WakeRoomData(String name, Integer capacity, BigDecimal basePrice, boolean special) {
}
