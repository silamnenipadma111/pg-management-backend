package com.pgmanagement.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "pg_media")
@Getter
@Setter
@NoArgsConstructor
public class PgMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pg_id", nullable = false)
    private Pg pg;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Enums.MediaType mediaType;

    @Column(nullable = false, length = 1000)
    private String url;

    @Column(nullable = false)
    private int sortOrder;
}
