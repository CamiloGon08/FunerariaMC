package com.example.funerariamc.model;

import lombok.NoArgsConstructor;
import jakarta.persistence.*;

/**
 * Subtipo de Employee: encargado de la cafetería interna de cortesía.
 * No maneja comisión ni se liga a Sale (no es una línea de negocio independiente).
 * Tabla: cafeteria_attendant
 */
@Entity
@Table(name = "cafeteria_attendant")
@NoArgsConstructor
public class CafeteriaAttendant extends Employee {
}
