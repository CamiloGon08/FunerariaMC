package com.example.funerariamc.service;

import com.example.funerariamc.model.enums.ParameterCategory;

/** Datos del formulario "Nuevo parámetro". */
public record ParameterData(String name, String value, String description, ParameterCategory category, String unit) {
}
