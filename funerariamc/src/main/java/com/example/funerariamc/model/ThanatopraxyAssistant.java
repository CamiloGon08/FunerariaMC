
package com.example.funerariamc.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

/**
 * Employee subtype that assists the thanatopractor in body preparation.
 * It has no commission, requires no training certificate, and has no system user.
 * Table: thanatopraxy_assistant.
 */
@Entity
@Table(name = "thanatopraxy_assistant")
@NoArgsConstructor
public class ThanatopraxyAssistant extends Employee {
}
