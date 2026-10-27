package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Beneficiary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** Beneficiarios de los planes exequiales (RF-PLA-02). */
public interface BeneficiaryRepository extends JpaRepository<Beneficiary, Long> {

    /** Nombre corto del beneficiario para las iniciales de las tarjetas. */
    interface BeneficiaryNameRow {
        Long getPlanId();
        String getName();
        String getLastname();
    }

    List<Beneficiary> findByFuneralPlanIdOrderByIdAsc(Long funeralPlanId);

    long countByFuneralPlanId(Long funeralPlanId);

    /** Un mismo documento no se repite dentro del plan. */
    boolean existsByFuneralPlanIdAndDocumentNumber(Long funeralPlanId, String documentNumber);

    /** Beneficiarios de varios planes a la vez, en el orden en que se registraron. */
    @Query("""
            select b.funeralPlan.id as planId, b.name as name, b.lastname as lastname
            from Beneficiary b
            where b.funeralPlan.id in :planIds
            order by b.funeralPlan.id, b.id
            """)
    List<BeneficiaryNameRow> findNames(@Param("planIds") Collection<Long> planIds);

    /** Plan que cubre un documento: el difunto puede ser beneficiario de un plan (HU-56). */
    @Query("""
            select b from Beneficiary b
            join fetch b.funeralPlan p
            join fetch p.client
            where b.documentNumber = :document
            order by p.startDate desc
            """)
    List<Beneficiary> findByDocumentWithPlan(@Param("document") String document);
}
