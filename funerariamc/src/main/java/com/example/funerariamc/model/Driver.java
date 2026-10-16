
package com.example.funerariamc.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDate;

/**
 * Represents a funeral home driver.
 */
@Entity
@Table(name = "driver")
@Getter
@Setter
@NoArgsConstructor
public class Driver extends Employee {

    @Column(name = "license_category")
    private String licenseCategory;

    @Column(name = "license_file_url")
    private String licenseFileUrl;
    
   @Column(name = "license_number")
    private String licenseNumber;
   
   @Column(name = "license_expiry_date")
   private LocalDate licenseExpiryDate;

}
