
package com.example.funerariamc.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

/**
 * Represents the employee responsible for complimentary cafeteria supplies.
 * This role has no commission or sales relationship.
 */
@Entity
@Table(name = "cafeteria_attendant")
@NoArgsConstructor
public class CafeteriaAttendant extends Employee {
}
