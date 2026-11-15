package com.pgmanagement.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "beds")
@Getter
@Setter
@NoArgsConstructor
public class Bed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @Column(nullable = false, length = 10)
    private String bedLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Enums.BedStatus status = Enums.BedStatus.AVAILABLE;

    @Version
    private Long version;
}
