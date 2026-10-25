package com.example.funerariamc.persistence;

import com.example.funerariamc.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.funerariamc.model.enums.EmployeeStatus;

import java.util.Collection;
import java.util.Optional;
import java.util.List;


/**trae el usuario con el empleado y su rol en una sola consulta, 
 * para que la sesion pueda usarlos
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long>{
    @Query("""
        select u from AppUser u 
        join fetch u.employee e
        left join fetch e.role
        where lower(u.username) = lower(:username)
        """)

    Optional<AppUser> findByUsernameWithEmployeeAndRole(@Param("username") String username);

    boolean existsByUsername(String username);
    boolean existsByEmployee_Id(Long employeeId);

    /** al habilitar o deshabilitar un empleado se activa o desactiva su cuenta */
    Optional<AppUser> findByEmployee_Id(Long employeeId);

    /** el nombre de usuario no se repite, sin importar mayusculas */
    boolean existsByUsernameIgnoreCase(String username);

    /** cuentas de los empleados que se ven en una pagina de Personal */
    List<AppUser> findByEmployee_IdIn(Collection<Long> employeeIds);

    /** indicador "Con cuenta de acceso" de Personal */
    long countByEmployee_Status(EmployeeStatus status);
}
