package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Deceased;
import com.example.funerariamc.model.FuneralPlan;
import com.example.funerariamc.model.enums.ClientType;
import com.example.funerariamc.model.enums.PlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;


public interface FuneralPlanRepository extends JpaRepository<FuneralPlan, Long>{

    /** Fila de la tarjeta de un plan */
    interface PlanCardRow{
        Long getPlanId();
        Long getCLientId();
        String getHolderName();
        String getHolderLastNAme();
        String getHolderDocumentType();
        String getHolderType();
        ClientType getHolderType();
        String getPlanName();
        BigDecimal getMonthlyFee();
        LocalDate getStartDate();
        PlanStatus getStatus();
        long getBeneficaries();
        long getServicesUsed();
    }
    
    @Query("""
        select p.id as planId, c.id as clientId, c.name as holderName, c.lastname as holderLastname,
                   c.documentType as holderDocumentType, c.documentNumber as holderDocument,
                   c.clientType as holderType, p.planName as planName, p.monthlyFee as monthlyFee,
                   p.startDate as startDate, p.status as status,
                   (select count(b) from Beneficiary b where b.funeralPlan = p) as beneficiaries,
                   (select count(d) from Deceased d where d.funeralPlan = p) as servicesUsed
            from FuneralPlan p join p.client c
            order by p.startDate desc, p.id desc
        """)
    List<PlanCardRow> findCards();

    /**Un plan con su titular para el panel de detalle */
        @Query("""
            select p from FuneralPlan p
            join fecth p.client
            where p.id = :id
            """)
        Optional<FuneralPlan> findDetailById(@Param("id") Long id);

        /** Planes de un cliente con un estado dado */
        List<FuneralPlan> findByClientIdAndStatus(Long clientId, PlanStatus status);

        /**Difuntos que usaron el plan */
        @Query("""
            select d from Deceased d
            where d.funeralPlan.id = :planId
            order by d.deathDate desc nulls last, d.id desc
            """)
        List<Deceased> findDeceasedByPlan(@Param("PlanId") Long planId);
}
