package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;

/**
 * Subtipo de Employee: encargado de la cafetería interna de cortesía.
 * No maneja comisión ni se liga a Sale (no es una línea de negocio independiente).
 * Tabla: cafeteria_attendant
 */
@Entity
@Table(name = "cafeteria_attendant")
@Getter
@Setter
@NoArgsConstructor
public class CafeteriaAttendant extends Employee {
}
