package com.hunt.otziv.performers.controller;

import com.hunt.otziv.performers.dto.RegisterPerformerRequest;
import com.hunt.otziv.performers.dto.RegisterPerformerResponse;
import com.hunt.otziv.performers.service.PerformerRegistrationService;
import com.hunt.otziv.c_cities.dto.CityDTO;
import com.hunt.otziv.c_cities.services.CityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class ApiPerformerAuthController {

    private final PerformerRegistrationService performerRegistrationService;
    private final CityService cityService;

    @GetMapping("/performer-cities")
    public List<CityDTO> performerCities() {
        return cityService.getAllCities();
    }

    @PostMapping("/register-performer")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterPerformerResponse registerPerformer(@Valid @RequestBody RegisterPerformerRequest request) {
        return performerRegistrationService.register(request);
    }
}
