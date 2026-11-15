package com.pgmanagement.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "rooms", uniqueConstraints = @UniqueConstraint(columnNames = {"pg_id", "room_number"}))
@Getter
@Setter
@NoArgsConstructor
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pg_id", nullable = false)
    private Pg pg;

    /** 0 = ground floor */
    @Column(nullable = false)
    private int floorNumber;

    @Column(name = "room_number", nullable = false, length = 20)
    private String roomNumber;

    /** Number of beds sharing the room (1 = single, 2 = double sharing ...) */
    @Column(nullable = false)
    private int sharingType;

    @Column(nullable = false)
    private boolean ac;

    @Column(nullable = false)
    private boolean attachedBathroom;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal dailyRent;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal weeklyRent;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyRent;

    @OneToMany(mappedBy = "room", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("bedLabel ASC")
    private List<Bed> beds = new ArrayList<>();
}
