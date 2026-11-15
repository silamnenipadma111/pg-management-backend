package com.pgmanagement.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/meta")
public class MetaController {

    public static final List<String> FACILITIES = List.of("FOOD", "WIFI", "WASHING_MACHINE", "CLEANING", "AC",
            "NON_AC", "SELF_COOKING", "FRIDGE", "HOT_WATER", "POWER_BACKUP", "PARKING", "CCTV", "TV", "GYM",
            "LIFT", "LAUNDRY", "SECURITY", "DRINKING_WATER");

    @GetMapping("/options")
    public Map<String, Object> options() {
        return Map.of(
                "facilities", FACILITIES,
                "sharingTypes", List.of(1, 2, 3, 4, 5, 6),
                "bookingTypes", List.of("DAILY", "WEEKLY", "MONTHLY", "YEARLY"),
                "genderPreferences", List.of("MALE", "FEMALE", "ANY"));
    }
}
