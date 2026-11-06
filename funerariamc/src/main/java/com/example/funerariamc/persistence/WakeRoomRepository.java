package com.example.funerariamc.persistence;

import com.example.funerariamc.model.WakeRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Salas de velación. */
public interface WakeRoomRepository extends JpaRepository<WakeRoom, Long> {

    /** Todas las salas en el orden en que se registraron. */
    List<WakeRoom> findAllByOrderByIdAsc();

    /** Evita dos salas con el mismo nombre. */
    boolean existsByNameIgnoreCase(String name);
}
