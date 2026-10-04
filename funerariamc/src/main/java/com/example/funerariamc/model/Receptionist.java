package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;

/** Subtipo de Employee: receptionist. No agrega columnas propias. Tabla: receptionist */
@Entity
@Table(name = "receptionist")
@Getter
@Setter
@NoArgsConstructor
public class Receptionist extends Employee {
}
