package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Vehículos de la funeraria. */
public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    /** lista todo los vehiculos ordenados por placa. */
    List<Vehicle> findAllByOrderByPlateAsc();

    /** La placa no se puede repetir. */
    boolean existsByPlateIgnoreCase(String plate);

    /** Indica si otro vehículo, distinto al que se edita, ya tiene esa placa. */
    boolean existsByPlateIgnoreCaseAndIdNot(String plate, Long id);
}