package com.pgmanagement.controller;

import com.pgmanagement.dto.CommonDtos.*;
import com.pgmanagement.service.SupportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/support")
@RequiredArgsConstructor
public class SupportController {

    private final SupportService supportService;

    /** Public: callback / PG registration / general requests from the Customer Care page. */
    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportRequestDto create(@Valid @RequestBody SupportRequestCreate req) {
        return supportService.create(req);
    }

    @GetMapping("/requests")
    public List<SupportRequestDto> list() {
        return supportService.list();
    }

    @PostMapping("/requests/{id}/resolve")
    public SupportRequestDto resolve(@PathVariable Long id) {
        return supportService.resolve(id);
    }

    @PostMapping("/pgs")
    @ResponseStatus(HttpStatus.CREATED)
    public AssistedPgResponse registerForOwner(@Valid @RequestBody AssistedPgRequest req) {
        return supportService.registerForOwner(req);
    }
}
