package com.example.funerariamc.persistence;

import com.example.funerariamc.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
