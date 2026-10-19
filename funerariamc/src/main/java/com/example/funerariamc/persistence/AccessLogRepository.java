package com.example.funerariamc.persistence;
import com.example.funerariamc.model.AccessLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;

import java.util.List;


public interface AccessLogRepository extends JpaRepository<AccessLog, Long>{
    /** ultimos registros de la bitacora con el usuario y su empleado ya cargados */
    @Query("""
        select l from AccessLog l
        join fetch l.appUser u
        join fetch u.employee
        order by l.timestamp desc, l.id desc
        """)
        List<AccessLog> findRecent(Pageable pageable);
}
