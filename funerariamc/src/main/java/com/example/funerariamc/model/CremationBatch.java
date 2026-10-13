package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDateTime;

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

    @Column(name = "furnace_code")
    private String furnaceCode;

    @Column(name = "status")
    private String status;
}