package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Lote/turno de cremación programado en el horno. Tabla: cremation_batch */
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

    @Column(name = "furnace_code", length = 15)
    private String furnaceCode;

    @Column(name = "status", length = 15)
    private String status;
}
