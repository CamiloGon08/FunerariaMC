package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Registro individual de cremación de un difunto. Tabla: cremation */
@Entity
@Table(name = "cremation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Cremation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cremation_batch_id", nullable = false)
    private CremationBatch cremationBatch;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deceased_id", nullable = false, unique = true)
    private Deceased deceased;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thanatopractor_id")
    private Thanatopractor thanatopractor;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "certificate_number", length = 20)
    private String certificateNumber;
}
