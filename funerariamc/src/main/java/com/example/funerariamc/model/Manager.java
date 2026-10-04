package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;

/** Subtipo de Employee: manager. No agrega columnas propias. Tabla: manager */
@Entity
@Table(name = "manager")
@Getter
@Setter
@NoArgsConstructor
public class Manager extends Employee {
}
