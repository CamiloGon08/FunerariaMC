package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.enums.EmployeeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

/** Sirve para Employee y sus subtipos  */
public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    Optional<Employee> findByDocumentNumber(String documentNumber);

    /**Pagina de la lista de personal */
    @EntityGraph(attributePaths = "role")
    @Query(value = """
            select e from Employee e
            where e.status in :statuses
              and type(e) in :types
              and (lower(concat(e.name, ' ', e.lastname)) like :pattern
                   or e.documentNumber like :pattern)
            order by e.lastname, e.name, e.id
            """,
            countQuery = """
            select count(e) from Employee e
            where e.status in :statuses
              and type(e) in :types
              and (lower(concat(e.name, ' ', e.lastname)) like :pattern
                   or e.documentNumber like :pattern)
            """)
    Page<Employee> search(@Param("pattern") String patetrn,
                        @Param("statuses") Collection<EmployeeStatus> statuses,
                        @Param("types") Collection<Class<? extends Employee>> types,
                        Pageable pageable);

    /**resume numero de empleados, activos e inactivos */
    long countByStatus(EmployeeStatus status);

    /** conductores con la licencia vencida o que vence hasta esa fecha (indicador "Documentos por vencer") */
    @Query("""
            select count(d) from Driver d
            where d.status = :status
              and d.licenseExpiryDate <= :limit
            """)
    long countDriversWithLicenseExpiringBy(@Param("status") EmployeeStatus status,
                                           @Param("limit") LocalDate limit);

    /** Los 4 indicadores de Personal en una sola consulta (antes eran 4 viajes a Supabase). */
    interface StaffCounts {
        long getTotal();
        long getActive();
        long getWithAccount();
        long getExpiring();
    }

    @Query(value = """
            select (select count(*) from employee) as total,
                   (select count(*) from employee where status = 'ACTIVE') as active,
                   (select count(*) from app_user u join employee e on e.id = u.employee_id
                     where e.status = 'ACTIVE') as withAccount,
                   (select count(*) from driver d join employee e on e.id = d.id
                     where e.status = 'ACTIVE' and d.license_expiry_date <= :limit) as expiring
            """, nativeQuery = true)
    StaffCounts countStaff(@Param("limit") LocalDate limit);

}