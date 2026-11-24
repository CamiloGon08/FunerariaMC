package com.example.funerariamc.service;

import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.WakeRoom;
import com.example.funerariamc.persistence.CremationProviderRepository;
import com.example.funerariamc.persistence.ServiceContractRepository;
import com.example.funerariamc.persistence.VaultRepository;
import com.example.funerariamc.persistence.WakeRoomRepository;
import com.example.funerariamc.service.CremationStockService.ProviderStock;
import com.example.funerariamc.utils.enums.ContractStatus;
import com.example.funerariamc.utils.enums.CremationPurchaseStatus;
import com.example.funerariamc.utils.enums.VaultStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Lo que queda libre para darle destino a un difunto: bóvedas disponibles, cremaciones prepagadas
 * sin usar y salas de velación libres en este momento. Lo usa la columna derecha de Difuntos.
 */
@Service
public class DestinationService {

    /** Ningún contrato tiene este id: sirve para preguntar por cruces sin excluir ninguno. */
    private static final Long NO_CONTRACT = -1L;
    private final VaultRepository vaultRepository;
    private final CremationProviderRepository providerRepository;
    private final WakeRoomRepository roomRepository;
    private final ServiceContractRepository contractRepository;
    private final SessionContext sessionContext;

    public DestinationService(VaultRepository vaultRepository, CremationProviderRepository providerRepository,
                              WakeRoomRepository roomRepository, ServiceContractRepository contractRepository,
                              SessionContext sessionContext) {
        this.vaultRepository = vaultRepository;
        this.providerRepository = providerRepository;
        this.roomRepository = roomRepository;
        this.contractRepository = contractRepository;
        this.sessionContext = sessionContext;
    }

    /**
     * Cupos libres y el total de cada uno, para mostrar "42 de 120" y llenar la barra.
     * receivedCremations son todas las cremaciones prepagadas que han llegado.
     */
    public record Availability(long freeVaults, long vaults, long prepaidCremations, long receivedCremations,
                               long freeRooms, long rooms) {
    }

    @Transactional(readOnly = true)
    public Availability availability() {
        requireAccess();
        long freeVaults = vaultRepository.countByStatus(VaultStatus.AVAILABLE);
        // Igual que en Cupos prepagados: recibidos menos separados menos cremados, por proveedor
        List<ProviderStock> stock = providerRepository.findStock(CremationPurchaseStatus.RECEIVED,
                        CremationPurchaseStatus.ORDERED, CremationPurchaseStatus.REQUESTED).stream()
                .map(CremationStockService::toStock)
                .toList();
        long cremations = stock.stream().mapToLong(ProviderStock::available).sum();
        long received = stock.stream().mapToLong(ProviderStock::received).sum();
        LocalDateTime now = LocalDateTime.now(AccessLogService.APP_ZONE);
        long rooms = 0;
        long free = 0;
        for (WakeRoom room : roomRepository.findAllByOrderByIdAsc()) {
            rooms++;
            if (!contractRepository.existsWakeOverlap(room.getId(), NO_CONTRACT, now, now.plusMinutes(1),
                    ContractStatus.CANCELLED)) {
                free++;
            }
        }
        return new Availability(freeVaults, vaultRepository.count(), cremations, received, free, rooms);
    }

    private void requireAccess() {
        boolean allowed = sessionContext.getRoleName()
                .map(AppModule.DIFUNTOS::isAllowedFor)
                .orElse(false);
        if (!allowed) {
            throw new UnauthorizedActionException();
        }
    }
}
