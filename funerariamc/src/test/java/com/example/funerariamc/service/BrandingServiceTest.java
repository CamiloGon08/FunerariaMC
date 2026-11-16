package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Branding;
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.persistence.BrandingRepository;
import com.example.funerariamc.service.BrandingService.BrandView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

class BrandingServiceTest {

    private static final byte[] PNG = image("png");
    private static final byte[] JPG = image("jpg");

    private static byte[] image(String format) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private final BrandingRepository repository = mock(BrandingRepository.class);
    private final AccessLogService audit = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final BrandingService service = new BrandingService(repository, audit, session);

    @BeforeEach
    void setUp() {
        login(RoleNames.MASTER_ADMIN);
        when(repository.findById(Branding.SINGLE_ROW)).thenReturn(Optional.empty());
        when(repository.save(any(Branding.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void login(String role) {
        Manager employee = new Manager();
        employee.setRole(new Role(1L, role, null));
        employee.setName("Ana");
        employee.setLastname("Pérez");
        AppUser user = new AppUser();
        user.setId(5L);
        user.setUsername("ana");
        user.setEmployee(employee);
        session.start(user);
    }

    private static BrandingData data(String name, byte[] logo) {
        return new BrandingData(name, "  Con ustedes siempre ", logo, false, "#1b2a3d", "#3D5A80", "#C9A96E");
    }

    @Test
    void withoutRowTheSystemUsesElEden() {
        when(repository.findCurrent()).thenReturn(Optional.empty());
        BrandView brand = service.current();
        assertEquals("El Edén", brand.commercialName());
        assertEquals("Funeraria El Edén", service.fullName());
        assertTrue(brand.edenLook());
        assertFalse(brand.hasCustomLogo());
        service.current();
        verify(repository, times(1)).findCurrent(); // queda en memoria
    }

    @Test
    void fullNameDoesNotRepeatFuneraria() {
        BrandView brand = new BrandView("Funeraria San José", null, null, null, "#000000", "#000000", "#000000", null, null);
        assertEquals("Funeraria San José", brand.fullName());
        assertEquals("Funeraria El Edén", BrandingService.fullNameOf(null));
    }

    @Test
    void masterSavesTheBrandLogsItAndTellsTheScreens() {
        List<BrandView> heard = new ArrayList<>();
        service.addListener(heard::add);
        BrandView saved = service.save(data("  Jardines de Paz ", PNG));

        ArgumentCaptor<Branding> row = ArgumentCaptor.forClass(Branding.class);
        verify(repository).save(row.capture());
        assertEquals(Short.valueOf(Branding.SINGLE_ROW), row.getValue().getId());
        assertEquals("Jardines de Paz", row.getValue().getCommercialName());
        assertEquals("Con ustedes siempre", row.getValue().getTagline());
        assertEquals("#1B2A3D", row.getValue().getPrimaryColor());
        assertEquals("image/png", row.getValue().getLogoType());
        assertNotNull(row.getValue().getUpdatedAt());
        assertEquals(5L, row.getValue().getUpdatedBy().getId());
        verify(audit).record(eq(5L), startsWith(AccessLogService.BRANDING_UPDATED + "name 'El Edén' -> 'Jardines de Paz'"));
        assertEquals(List.of(saved), heard);
        assertEquals("Funeraria Jardines de Paz", service.fullName());
        assertFalse(saved.edenLook());
        assertEquals("Ana Pérez", saved.updatedBy());
    }

    @Test
    void onlyTheMasterAdministratorCanChangeIt() {
        login(RoleNames.SECRETARY);
        assertThrows(UnauthorizedActionException.class, () -> service.save(data("Otra", null)));
        session.clear();
        assertThrows(UnauthorizedActionException.class, () -> service.save(data("Otra", null)));
        verify(repository, never()).save(any());
        verify(audit, never()).record(anyLong(), anyString());
    }

    @Test
    void invalidDataIsRejected() {
        assertThrows(InvalidDataException.class, () -> service.save(data("  ", null)));
        assertThrows(InvalidDataException.class, () -> service.save(data("x".repeat(61), null)));
        assertThrows(InvalidDataException.class, () -> service.save(
                new BrandingData("Otra", null, null, false, "azul", "#3D5A80", "#C9A96E")));
        InvalidDataException light = assertThrows(InvalidDataException.class, () -> service.save(
                new BrandingData("Otra", null, null, false, "#F2F2F2", "#3D5A80", "#C9A96E")));
        assertTrue(light.getMessage().contains("color principal es muy claro"));
        verify(repository, never()).save(any());
    }

    @Test
    void logoMustBeASmallPngOrJpg() {
        assertEquals("image/png", BrandingService.logoType(PNG));
        assertEquals("image/jpeg", BrandingService.logoType(JPG));
        assertThrows(InvalidDataException.class, () -> BrandingService.logoType("GIF89a....".getBytes()));
        assertThrows(InvalidDataException.class, () -> BrandingService.logoType(new byte[0]));
        byte[] big = new byte[BrandingService.MAX_LOGO_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThrows(InvalidDataException.class, () -> BrandingService.logoType(big));
    }

    @Test
    void restoreGoesBackToElEdenAndRemovesTheLogo() {
        Branding stored = new Branding();
        stored.setId(Branding.SINGLE_ROW);
        stored.setCommercialName("Jardines de Paz");
        stored.setLogo(PNG);
        stored.setLogoType("image/png");
        stored.setPrimaryColor("#1B2A3D");
        stored.setAccentColor("#3D5A80");
        stored.setDetailColor("#C9A96E");
        when(repository.findById(Branding.SINGLE_ROW)).thenReturn(Optional.of(stored));
        BrandView brand = service.restoreDefaults();
        assertTrue(brand.edenLook());
        assertNull(stored.getLogo());
        verify(audit).record(eq(5L), contains("logo removed"));
    }

    @Test
    void bandAndMenuImagesCanBeOwnLilyOrNone() {
        BrandView saved = service.save(new BrandingData("Otra", null, null, false, "#1B2A3D", "#3D5A80", "#C9A96E",
                BrandingData.ImageChoice.CUSTOM, JPG, BrandingData.ImageChoice.NONE, null));
        assertTrue(saved.hasCustomBandImage());
        assertFalse(saved.showMenuImage());
        assertFalse(saved.hasCustomMenuImage());
        verify(audit).record(eq(5L), contains("band image custom; menu image none"));
        // Imagen propia sin elegir archivo y sin una guardada: se pide elegirla
        assertThrows(InvalidDataException.class, () -> service.save(new BrandingData("Otra", null, null, false,
                "#1B2A3D", "#3D5A80", "#C9A96E", BrandingData.ImageChoice.EDEN, null,
                BrandingData.ImageChoice.CUSTOM, null)));
        assertThrows(InvalidDataException.class, () -> service.save(new BrandingData("Otra", null, null, false,
                "#1B2A3D", "#3D5A80", "#C9A96E", BrandingData.ImageChoice.CUSTOM, "texto".getBytes(),
                BrandingData.ImageChoice.EDEN, null)));
    }

    @Test
    void restoreAlsoBringsBackTheLilies() {
        Branding stored = new Branding();
        stored.setCommercialName("Otra");
        stored.setPrimaryColor("#1B2A3D");
        stored.setAccentColor("#3D5A80");
        stored.setDetailColor("#C9A96E");
        stored.setBandImage(PNG);
        stored.setBandImageType("image/png");
        stored.setShowMenuImage(false);
        when(repository.findById(Branding.SINGLE_ROW)).thenReturn(Optional.of(stored));
        BrandView brand = service.restoreDefaults();
        assertNull(stored.getBandImage());
        assertTrue(stored.getShowMenuImage());
        assertTrue(brand.showBandImage() && brand.showMenuImage());
        assertEquals("Funeraria El Edén", brand.fullName());
    }

    @Test
    void changesListOnlyWhatChanged() {
        BrandView eden = BrandingService.defaults();
        assertEquals("no changes", BrandingService.changes(eden, eden));
        BrandView renamed = new BrandView("Otra", eden.tagline(), null, null, eden.primaryColor(), eden.accentColor(),
                eden.detailColor(), null, null);
        assertEquals("name 'El Edén' -> 'Otra'", BrandingService.changes(eden, renamed));
    }

    @Test
    void imageHeadersAloneAreRejected() {
        assertThrows(InvalidDataException.class, () -> BrandingService.logoType(
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0}));
        assertThrows(InvalidDataException.class, () -> BrandingService.logoType(
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0}));
    }

    @Test
    void missingRequestIsABusinessError() {
        assertThrows(InvalidDataException.class, () -> service.save(null));
        verify(repository, never()).save(any());
    }

    @Test
    void longChangesFitTheAuditColumn() {
        Branding stored = new Branding();
        stored.setCommercialName("A".repeat(60));
        stored.setPrimaryColor(BrandPalette.DEFAULT_PRIMARY);
        stored.setAccentColor(BrandPalette.DEFAULT_ACCENT);
        stored.setDetailColor(BrandPalette.DEFAULT_DETAIL);
        when(repository.findById(Branding.SINGLE_ROW)).thenReturn(Optional.of(stored));
        service.save(data("B".repeat(60), PNG));
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(5L), action.capture());
        assertTrue(action.getValue().length() <= 100);
        assertTrue(action.getValue().startsWith(AccessLogService.BRANDING_UPDATED));
    }

    @Test
    void arraysCannotAlterAnAuthorizedCachedBrand() {
        byte[] input = PNG.clone();
        BrandingData request = data("Otra", input);
        input[0] = 0;
        request.logo()[0] = 0;
        BrandView result = service.save(request);
        result.logo()[0] = 0;
        assertArrayEquals(PNG, service.current().logo());
    }

    @Test
    void cacheAndListenersWaitUntilCommit() {
        when(repository.findCurrent()).thenReturn(Optional.empty());
        BrandView original = service.current();
        List<BrandView> heard = new ArrayList<>();
        service.addListener(heard::add);
        TransactionSynchronizationManager.initSynchronization();
        try {
            BrandView saved = service.save(data("Otra", null));
            assertSame(original, service.current());
            assertTrue(heard.isEmpty());
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertSame(saved, service.current());
            assertEquals(List.of(saved), heard);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackDoesNotPublishTheBrand() {
        when(repository.findCurrent()).thenReturn(Optional.empty());
        BrandView original = service.current();
        List<BrandView> heard = new ArrayList<>();
        service.addListener(heard::add);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.save(data("Otra", null));
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertSame(original, service.current());
            assertTrue(heard.isEmpty());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void auditFailurePreventsPublishingAndPropagatesForRollback() {
        when(repository.findCurrent()).thenReturn(Optional.empty());
        BrandView original = service.current();
        List<BrandView> heard = new ArrayList<>();
        service.addListener(heard::add);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(anyLong(), anyString());
        assertThrows(IllegalStateException.class, () -> service.save(data("Otra", null)));
        assertSame(original, service.current());
        assertTrue(heard.isEmpty());
    }

    @Test
    void oneBrokenScreenDoesNotPreventOtherScreensFromUpdating() {
        List<BrandView> heard = new ArrayList<>();
        service.addListener(brand -> { throw new IllegalStateException("closed screen"); });
        service.addListener(heard::add);
        BrandView saved = assertDoesNotThrow(() -> service.save(data("Otra", null)));
        assertEquals(List.of(saved), heard);
    }

    private static String contains(String text) {
        return org.mockito.ArgumentMatchers.contains(text);
    }
}
