package com.example.funerariamc.service;

import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.persistence.RoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Consulta del catálogo persistido de roles para administrar cuentas de usuario. */
@Service
@Transactional(readOnly = true)
public class RoleService {

    private final RoleRepository roleRepository;

    public RoleService(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    /** Devuelve los roles existentes sin crear ni filtrar registros del catálogo. */
    public List<Role> findAll() {
        return roleRepository.findAll();
    }

    /** Busca un rol existente; la ausencia se informa como una excepción de negocio. */
    public Role findById(Long roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Rol", roleId));
    }
}
