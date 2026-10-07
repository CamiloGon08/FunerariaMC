package com.example.funerariamc.model;


import lombok.NoArgsConstructor;
import jakarta.persistence.*;

/** Subtipo de Employee: receptionist. No agrega columnas propias. Tabla: receptionist */
@Entity
@Table(name = "receptionist")
@NoArgsConstructor
public class Receptionist extends Employee {
}
