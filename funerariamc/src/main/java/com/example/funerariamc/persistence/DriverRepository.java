package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Driver;
import com.example.funerariamc.model.enums.EmployeeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    /** Datos del conductor que muestra Transporte. */
    interface DriverRow {
        Long getId();
        String getName();
        String getLastname();
        String getDocumentNumber();
        String getPhone();
        String getLicenseCategory();
        String getLicenseNumber();
        LocalDate getLicenseExpiryDate();
    }

    @Query("""
            select d.id as id, d.name as name, d.lastname as lastname, d.documentNumber as documentNumber,
                   d.phone as phone, d.licenseCategory as licenseCategory, d.licenseNumber as licenseNumber,
                   d.licenseExpiryDate as licenseExpiryDate
            from Driver d
            where d.status = :status
            order by d.name, d.lastname
            """)
    List<DriverRow> findRowsByStatus(@Param("status") EmployeeStatus status);
}
