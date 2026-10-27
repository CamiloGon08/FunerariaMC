package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Beneficiary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

    /**Beneficiario de los planes exequiales */
public interface BeneficiaryRepository extends JpaRepository<Beneficiary, Long>{

    /**Nombre corto del beneficiario para las iniciales de las tarjetas */
    interface BeneficiaryNameRow{
        Long getPlanId();
        String getName();
        String getLastName();
    }

    List<Beneficiary> findByFuneralPlanIdAndDocumentNumber(Long funeralPlanId, String documentNumber);

    long countByFuneralPlanId(Long funeralPlanID);

    /** Un mismo documento no se puede repetir dentro del plan*/ 
    boolean existsByFuneralPlanIdAndDocumentNumber(Long funeralPlanId);

    /**Muestra a lo beneficiarios de varios planes en el orden en que fueron registrados */
    @Query("""
        select b.funeralPlan.id as planId, b.name as name, b.lastname as lastname
        from Beneficiary b
        where b.funeralPlan.id in :planIds
        order  by b.funerariaPlan.id, b.id
        """)
    List<BeneficiaryNameRow> findNames(@Param("planIds") Collection<Long> planIds);

    /**documento que cubre a un difunto como beneficiario */
    @Query("""
        select b from Benefiary b
        join fetch b.funeralPlan p
        join fetch p.client
        where b.doumentNumber = :document
        order by p.startDate desc
        """)
    List<Beneficiary> findByDocumentWitjPlan(@Param("document") String document);
}
