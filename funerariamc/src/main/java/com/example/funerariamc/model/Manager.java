
package com.example.funerariamc.model;

import lombok.NoArgsConstructor;
import jakarta.persistence.*;

/** Represents a manager. Table: manager. */
@Entity
@Table(name = "manager")
@NoArgsConstructor
public class Manager extends Employee {
}
