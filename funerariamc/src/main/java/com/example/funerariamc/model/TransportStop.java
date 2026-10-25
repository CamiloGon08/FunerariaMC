package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.StopType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Stop on the route of a transfer (origin, pickup, ceremony, destination).
 * Latitude and longitude are geocoded once, when the stop is registered.
 */
@Entity
@Table(
    name = "transport_stop",
    uniqueConstraints = @UniqueConstraint(
        columnNames = {"service_transport_id", "stop_order"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TransportStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_transport_id", nullable = false)
    private ServiceTransport serviceTransport;

    @Column(name = "stop_order", nullable = false)
    private Integer stopOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "stop_type", nullable = false, length = 20)
    private StopType stopType;

    @Column(name = "place_name", nullable = false, length = 120)
    private String placeName;

    @Column(name = "address", length = 200)
    private String address;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "planned_at")
    private LocalDateTime plannedAt;

    @Column(name = "arrived_at")
    private LocalDateTime arrivedAt;
}