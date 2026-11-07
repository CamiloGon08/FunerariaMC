package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDateTime;

import com.example.funerariamc.model.enums.CremationBatchStatus;

/**
 * Stores a scheduled cremation batch.
 */
@Entity
@Table(name = "cremation_batch")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CremationBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scheduled_date")
    private LocalDateTime scheduledDate;

    // Código del horno o del proveedor externo (máximo 15 caracteres en la BD).
    @Column(name = "furnace_code", length = 15)
    private String furnaceCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 15)
    private CremationBatchStatus status;
}