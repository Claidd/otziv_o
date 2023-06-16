package com.hunt.otziv.a_login.controller;


import com.hunt.otziv.a_login.dto.JwtRequest;
import com.hunt.otziv.a_login.dto.RegistrationUserDTO;
import com.hunt.otziv.a_login.services.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;


    @PostMapping("/auth")
    public ResponseEntity<?> createAuthToken(@RequestBody JwtRequest authRequest){

        return authService.createAuthToken(authRequest);
    }

    @PostMapping("/registration")
    public ResponseEntity<?> createAuthToken(@RequestBody RegistrationUserDTO registrationUserDTO){
        return authService.createNewUsers(registrationUserDTO);
    }

    @GetMapping("/index2")
    public String index2(){
        return  "WORKER IS WORK";
    }
}
