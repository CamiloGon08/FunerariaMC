package com.example.funerariamc.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Subtipo de Employee: tanatopractor(a). Tabla: thanatopractor */
@Entity
@Table(name = "thanatopractor")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Thanatopractor extends Employee {

    @Column(name = "training_institution", length = 80)
    private String trainingInstitution;

    @Column(name = "certification_date")
    private LocalDate certificationDate;

    @Column(name = "certificate_file_url", length = 250)
    private String certificateFileUrl;

    @Column(name = "commission_per_case", precision = 10, scale = 2)
    private BigDecimal commissionPerCase;
}
