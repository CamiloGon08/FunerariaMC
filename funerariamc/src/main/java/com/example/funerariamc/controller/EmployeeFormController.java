package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.model.Driver;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.FuneralSalesperson;
import com.example.funerariamc.model.Thanatopractor;
import com.example.funerariamc.model.enums.EmployeePosition;
import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.service.EmployeeData;
import com.example.funerariamc.service.EmployeeService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Formulario de Personal (mockup 09). Registra (HU-02) o actualiza (HU-03)
 * un empleado y muestra solo la sección del cargo elegido.
 * Las validaciones de negocio están en {@link EmployeeService}; aquí solo se
 * convierten los textos a números y fechas.
 */
@Component
@Scope("prototype")
public class EmployeeFormController {

    /** Código guardado en person.document_type → texto en pantalla. */
    private static final Map<String, String> DOCUMENT_TYPES = new LinkedHashMap<>();

    static {
        DOCUMENT_TYPES.put("CC", "Cédula de ciudadanía");
        DOCUMENT_TYPES.put("CE", "Cédula de extranjería");
        DOCUMENT_TYPES.put("PA", "Pasaporte");
    }

    private static final List<String> LICENSE_CATEGORIES = List.of("B1", "B2", "B3", "C1", "C2", "C3");

    /** Fechas en pantalla como dd/MM/yyyy, sin depender del idioma del equipo. */
    private static final StringConverter<LocalDate> DATE_CONVERTER = new StringConverter<>() {
        @Override
        public String toString(LocalDate date) {
            return date == null ? "" : EmployeeController.DATE.format(date);
        }

        @Override
        public LocalDate fromString(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return LocalDate.parse(text.trim(), EmployeeController.DATE);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    };

    private final EmployeeService employeeService;

    @FXML
    private Label titleLabel;
    @FXML
    private Label subtitleLabel;
    @FXML
    private TextField nameField;
    @FXML
    private TextField lastnameField;
    @FXML
    private DatePicker birthDatePicker;
    @FXML
    private ComboBox<String> documentTypeCombo;
    @FXML
    private TextField documentField;
    @FXML
    private TextField phoneField;
    @FXML
    private TextField emailField;
    @FXML
    private TextField addressField;
    @FXML
    private ComboBox<EmployeePosition> positionCombo;
    @FXML
    private DatePicker hireDatePicker;
    @FXML
    private TextField salaryField;
    @FXML
    private Label accountNote;
    @FXML
    private VBox salespersonSection;
    @FXML
    private TextField commissionRateField;
    @FXML
    private VBox thanatopractorSection;
    @FXML
    private TextField institutionField;
    @FXML
    private DatePicker certificationDatePicker;
    @FXML
    private TextField commissionPerCaseField;
    @FXML
    private Label certificateLabel;
    @FXML
    private VBox driverSection;
    @FXML
    private ComboBox<String> licenseCategoryCombo;
    @FXML
    private TextField licenseNumberField;
    @FXML
    private DatePicker licenseExpiryPicker;
    @FXML
    private Label licenseFileLabel;
    @FXML
    private Label licenseWarning;
    @FXML
    private Label errorLabel;
    @FXML
    private Button saveButton;

    private Employee employee;
    private Consumer<Employee> onSaved;
    private String certificateFileUrl;
    private String licenseFileUrl;

    public EmployeeFormController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @FXML
    private void initialize() {
        documentTypeCombo.getItems().addAll(DOCUMENT_TYPES.keySet());
        documentTypeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String code) {
                return code == null ? "" : DOCUMENT_TYPES.getOrDefault(code, code);
            }

            @Override
            public String fromString(String text) {
                return text;
            }
        });
        documentTypeCombo.setValue("CC");

        positionCombo.getItems().addAll(EmployeePosition.values());
        positionCombo.setButtonCell(positionCell());
        positionCombo.setCellFactory(list -> positionCell());
        positionCombo.valueProperty().addListener((obs, old, position) -> showSectionFor(position));

        licenseCategoryCombo.getItems().addAll(LICENSE_CATEGORIES);
        for (DatePicker picker : List.of(birthDatePicker, hireDatePicker, certificationDatePicker, licenseExpiryPicker)) {
            picker.setConverter(DATE_CONVERTER);
            picker.setShowWeekNumbers(false);
        }
        // Nacimiento, ingreso y certificación no pueden ser futuros; el vencimiento de la licencia sí.
        for (DatePicker picker : List.of(birthDatePicker, hireDatePicker, certificationDatePicker)) {
            picker.setDayCellFactory(p -> new DateCell() {
                @Override
                public void updateItem(LocalDate item, boolean empty) {
                    super.updateItem(item, empty);
                    setDisable(empty || item == null || item.isAfter(LocalDate.now(AccessLogService.APP_ZONE)));
                }
            });
        }
        licenseExpiryPicker.valueProperty().addListener((obs, old, date) -> updateLicenseWarning());

        // Una sección oculta no ocupa espacio.
        for (javafx.scene.Node node : List.of(salespersonSection, thanatopractorSection, driverSection,
                accountNote, licenseWarning, errorLabel)) {
            node.managedProperty().bind(node.visibleProperty());
        }
        errorLabel.visibleProperty().bind(errorLabel.textProperty().isNotEmpty());
        showSectionFor(null);
    }

    /**
     * Prepara el formulario. {@code employee} en null = registrar.
     * {@code onSaved} recibe el empleado guardado para refrescar la lista.
     */
    public void init(Employee employee, Consumer<Employee> onSaved) {
        this.employee = employee;
        this.onSaved = onSaved;
        if (employee == null) {
            titleLabel.setText("Registrar empleado");
            hireDatePicker.setValue(LocalDate.now(AccessLogService.APP_ZONE));
            return;
        }
        titleLabel.setText("Actualizar empleado");
        subtitleLabel.setText(EmployeeController.fullName(employee) + " · "
                + EmployeeController.documentLabel(employee));
        saveButton.setText("Guardar cambios");

        nameField.setText(employee.getName());
        lastnameField.setText(employee.getLastname());
        birthDatePicker.setValue(employee.getBirthDate());
        documentTypeCombo.setValue(employee.getDocumentType());
        documentField.setText(employee.getDocumentNumber());
        phoneField.setText(employee.getPhone());
        emailField.setText(employee.getEmail());
        addressField.setText(employee.getAddress());
        hireDatePicker.setValue(employee.getHireDate());
        salaryField.setText(pesos(employee.getSalary()));

        // El cargo es la tabla hija: no se cambia al actualizar.
        positionCombo.setValue(EmployeePosition.of(employee));
        positionCombo.setDisable(true);

        if (employee instanceof FuneralSalesperson salesperson) {
            commissionRateField.setText(plain(salesperson.getCommissionRate()));
        } else if (employee instanceof Thanatopractor thanatopractor) {
            institutionField.setText(thanatopractor.getTrainingInstitution());
            certificationDatePicker.setValue(thanatopractor.getCertificationDate());
            commissionPerCaseField.setText(pesos(thanatopractor.getCommissionPerCase()));
            certificateFileUrl = thanatopractor.getCertificateFileUrl();
            certificateLabel.setText(fileName(certificateFileUrl));
        } else if (employee instanceof Driver driver) {
            licenseCategoryCombo.setValue(driver.getLicenseCategory());
            licenseNumberField.setText(driver.getLicenseNumber());
            licenseExpiryPicker.setValue(driver.getLicenseExpiryDate());
            licenseFileUrl = driver.getLicenseFileUrl();
            licenseFileLabel.setText(fileName(licenseFileUrl));
        }
    }

    private void showSectionFor(EmployeePosition position) {
        salespersonSection.setVisible(position == EmployeePosition.FUNERAL_SALESPERSON);
        thanatopractorSection.setVisible(position == EmployeePosition.THANATOPRACTOR);
        driverSection.setVisible(position == EmployeePosition.DRIVER);
        accountNote.setVisible(position != null);
        if (position != null) {
            accountNote.setText(position.canHaveUserAccount()
                    ? "Este cargo puede tener cuenta de usuario. La cuenta se crea aparte, en Cuentas de usuario."
                    : "Este cargo no tiene acceso al sistema, así que no tendrá cuenta de usuario (RN-23).");
        }
        updateLicenseWarning();
    }

    /** RN-26: avisa, pero deja guardar. */
    private void updateLicenseWarning() {
        licenseWarning.setVisible(positionCombo.getValue() == EmployeePosition.DRIVER
                && EmployeeController.isPast(licenseExpiryPicker.getValue()));
    }

    @FXML
    private void onChooseCertificate() {
        File file = chooseFile("Certificado del tanatólogo");
        if (isReadable(file)) {
            certificateFileUrl = file.toURI().toString();
            certificateLabel.setText(file.getName());
        }
    }

    @FXML
    private void onChooseLicenseFile() {
        File file = chooseFile("Licencia de conducción");
        if (isReadable(file)) {
            licenseFileUrl = file.toURI().toString();
            licenseFileLabel.setText(file.getName());
        }
    }

    /**
     * RN-25: en la BD solo va la URL del archivo. Mientras no haya un servicio
     * de almacenamiento se guarda la ruta local (file:/...) del archivo elegido.
     */
    private File chooseFile(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PDF o imagen", "*.pdf", "*.png", "*.jpg", "*.jpeg"));
        return chooser.showOpenDialog(window());
    }

    /** HU-03: si el archivo no se puede leer, no se toma y se pide elegirlo otra vez. */
    private boolean isReadable(File file) {
        if (file == null) {
            return false; // canceló la selección
        }
        if (!Files.isReadable(file.toPath())) {
            errorLabel.setText("No se pudo cargar el archivo " + file.getName() + ". Intente de nuevo.");
            return false;
        }
        errorLabel.setText("");
        return true;
    }

    @FXML
    private void onSave() {
        errorLabel.setText("");
        try {
            EmployeeData data = readForm();
            Employee saved = employee == null
                    ? employeeService.register(data)
                    : employeeService.update(employee.getId(), data);
            if (onSaved != null) {
                onSaved.accept(saved);
            }
            window().close();
        } catch (NumberFormatException e) {
            errorLabel.setText(e.getMessage());
        } catch (BusinessException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    @FXML
    private void onCancel() {
        window().close();
    }

    private EmployeeData readForm() {
        EmployeePosition position = positionCombo.getValue();
        boolean salesperson = position == EmployeePosition.FUNERAL_SALESPERSON;
        boolean thanatopractor = position == EmployeePosition.THANATOPRACTOR;
        boolean driver = position == EmployeePosition.DRIVER;
        return new EmployeeData(
                text(nameField),
                text(lastnameField),
                documentTypeCombo.getValue(),
                text(documentField),
                text(phoneField),
                text(emailField),
                text(addressField),
                birthDatePicker.getValue(),
                position,
                hireDatePicker.getValue(),
                money(salaryField, "El salario"),
                salesperson ? percent(commissionRateField) : null,
                thanatopractor ? text(institutionField) : null,
                thanatopractor ? certificationDatePicker.getValue() : null,
                thanatopractor ? certificateFileUrl : null,
                thanatopractor ? money(commissionPerCaseField, "La comisión por caso") : null,
                driver ? licenseCategoryCombo.getValue() : null,
                driver ? text(licenseNumberField) : null,
                driver ? licenseExpiryPicker.getValue() : null,
                driver ? licenseFileUrl : null);
    }

    // ---------- conversión de textos ----------

    private static String text(TextField field) {
        String value = field.getText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Pesos sin decimales: acepta "1.800.000", "1800000" o "$ 1 800 000". */
    private static BigDecimal money(TextField field, String what) {
        String value = text(field);
        if (value == null) {
            return null; // el servicio dice que es obligatorio
        }
        String digits = value.replaceAll("[$.,\\s]", "");
        if (!digits.matches("\\d+")) {
            throw new NumberFormatException(what + " debe ser un valor en pesos, por ejemplo 1.800.000.");
        }
        return new BigDecimal(digits);
    }

    /** Porcentaje con coma o punto decimal: "5", "5,5" o "5.5". */
    private static BigDecimal percent(TextField field) {
        String value = text(field);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.replace("%", "").replace(',', '.').trim());
        } catch (NumberFormatException e) {
            throw new NumberFormatException("El porcentaje de comisión debe ser un número, por ejemplo 5 o 5,5.");
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    /** 1850000 → "1.850.000", igual que el ejemplo del campo. */
    private static String pesos(BigDecimal value) {
        return value == null ? "" : String.format("%,d", value.longValue()).replace(',', '.');
    }

    private static String fileName(String url) {
        if (url == null || url.isBlank()) {
            return "Ningún archivo";
        }
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private static ListCell<EmployeePosition> positionCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(EmployeePosition item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "Seleccione" : item.getDisplayName());
            }
        };
    }

    private Stage window() {
        return (Stage) saveButton.getScene().getWindow();
    }
}
