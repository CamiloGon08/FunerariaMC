
package com.example.funerariamc.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

/**
 * Represents an assistant who supports body preparation.
 */
@Entity
@Table(name = "thanatopraxy_assistant")
@NoArgsConstructor
public class ThanatopraxyAssistant extends Employee {
}
