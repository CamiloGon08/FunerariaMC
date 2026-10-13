
package com.example.funerariamc.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Represents an employee who sells funeral services and plans.
 */
@Entity
@Table(name = "funeral_salesperson")
@Getter
@Setter
@NoArgsConstructor
public class FuneralSalesperson extends Employee {

    @Column(name = "commission_rate")
    private BigDecimal commissionRate;
}
