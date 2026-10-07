package com.example.funerariamc.model;

import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;

/** Subtipo de Employee: manager. No agrega columnas propias. Tabla: manager */
@Entity
@Table(name = "manager")
@NoArgsConstructor
public class Manager extends Employee {
}
