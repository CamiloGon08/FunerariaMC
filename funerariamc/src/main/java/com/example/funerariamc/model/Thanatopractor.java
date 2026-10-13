
package com.example.funerariamc.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Represents a thanatopractor.
 */
@Entity
@Table(name = "thanatopractor")
@Getter
@Setter
@NoArgsConstructor
public class Thanatopractor extends Employee {

    @Column(name = "training_institution")
    private String trainingInstitution;

    @Column(name = "certification_date")
    private LocalDate certificationDate;

    @Column(name = "certificate_file_url")
    private String certificateFileUrl;

    @Column(name = "commission_per_case")
    private BigDecimal commissionPerCase;
}
