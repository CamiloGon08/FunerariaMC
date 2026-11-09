package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.FuneralSalesperson;
import com.example.funerariamc.model.Thanatopractor;
import com.example.funerariamc.model.enums.EmployeeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Vendedores y tanatólogos activos para las listas de Contratos y ventas. */
public interface StaffOptionRepository extends JpaRepository<Employee, Long> {

    /** Vendedores funerarios activos (la venta guarda quién la gestionó). */
    @Query("""
            select s from FuneralSalesperson s
            where s.status = :active
            order by s.name, s.lastname
            """)
    List<Employee> findActiveSalespeople(@Param("active") EmployeeStatus active);

    /** Tanatólogos activos (el contrato guarda quién hizo la preparación). */
    @Query("""
            select t from Thanatopractor t
            where t.status = :active
            order by t.name, t.lastname
            """)
    List<Employee> findActiveThanatopractors(@Param("active") EmployeeStatus active);

    /** Todos los vendedores funerarios con su porcentaje de comisión, activos o no. */
    @Query("select s from FuneralSalesperson s order by s.name, s.lastname")
    List<FuneralSalesperson> findAllSalespeople();

    /** Todos los tanatólogos con su tarifa por caso, activos o no. */
    @Query("select t from Thanatopractor t order by t.name, t.lastname")
    List<Thanatopractor> findAllThanatopractors();
}

