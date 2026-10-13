
package com.example.funerariamc.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

/**
 * Represents an employee responsible for customer service and administrative tasks.
 */
@Entity
@Table(name = "receptionist")
@NoArgsConstructor
public class Receptionist extends Employee {
}
