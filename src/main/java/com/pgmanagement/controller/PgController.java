package com.pgmanagement.controller;

import com.pgmanagement.dto.PgDtos.*;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.service.PgService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/** Public PG browsing APIs. */
@RestController
@RequestMapping("/api/pgs")
@RequiredArgsConstructor
public class PgController {

    private final PgService pgService;

    @GetMapping
    public List<PgSummaryDto> search(@RequestParam(required = false) String q,
                                     @RequestParam(required = false) String city,
                                     @RequestParam(required = false) Integer sharing,
                                     @RequestParam(required = false) BigDecimal minRent,
                                     @RequestParam(required = false) BigDecimal maxRent,
                                     @RequestParam(required = false) Enums.GenderPreference gender,
                                     @RequestParam(required = false) Boolean ac,
                                     @RequestParam(required = false) Double lat,
                                     @RequestParam(required = false) Double lng,
                                     @RequestParam(required = false) Double radiusKm,
                                     @RequestParam(required = false) String sort) {
        return pgService.search(new SearchCriteria(q, city, sharing, minRent, maxRent, gender, ac, lat, lng,
                radiusKm, sort));
    }

    @GetMapping("/{id}")
    public PgDetailDto detail(@PathVariable Long id) {
        return pgService.getDetail(id);
    }
}
