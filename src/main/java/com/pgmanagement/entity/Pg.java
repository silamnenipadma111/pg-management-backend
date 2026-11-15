package com.pgmanagement.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pgs")
@Getter
@Setter
@NoArgsConstructor
public class Pg {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false, length = 300)
    private String addressLine;

    @Column(nullable = false, length = 100)
    private String area;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(nullable = false, length = 100)
    private String state;

    @Column(nullable = false, length = 10)
    private String pincode;

    private Double latitude;

    private Double longitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Enums.GenderPreference genderPreference = Enums.GenderPreference.ANY;

    /** Comma separated facility codes, e.g. FOOD,WIFI,WASHING_MACHINE */
    @Column(length = 1000)
    private String facilities;

    @Column(nullable = false, length = 20)
    private String contactPhone;

    @Column(length = 2000)
    private String houseRules;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Enums.RegisteredVia registeredVia = Enums.RegisteredVia.OWNER;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "pg", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<PgMedia> media = new ArrayList<>();

    @OneToMany(mappedBy = "pg", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("floorNumber ASC, roomNumber ASC")
    private List<Room> rooms = new ArrayList<>();
}
