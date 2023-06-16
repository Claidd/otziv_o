package com.hunt.otziv.a_login.services;

import com.hunt.otziv.a_login.dto.JwtRequest;
import com.hunt.otziv.a_login.dto.JwtResponse;
import com.hunt.otziv.a_login.dto.RegistrationUserDTO;
import com.hunt.otziv.a_login.exeptions.AppError;
import com.hunt.otziv.a_login.model.User;
import com.hunt.otziv.a_login.utils.JwtTokenUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestBody;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserServiceImpl userService;
    private final JwtTokenUtils jwtTokenUtils;
    private final AuthenticationManager authenticationManager;

    public ResponseEntity<?> createAuthToken(@RequestBody JwtRequest authRequest){
        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(authRequest.getUsername(), authRequest.getPassword()));
        }
        catch (BadCredentialsException e){
            //выбрасываем исключение если не авторизировались
            return new ResponseEntity<>(new AppError(HttpStatus.UNAUTHORIZED.value(), "Не правильный логин или пароль"), HttpStatus.UNAUTHORIZED);
        }

        UserDetails userDetails = userService.loadUserByUsername(authRequest.getUsername());
        String token = jwtTokenUtils.generateToken(userDetails);
        return ResponseEntity.ok(new JwtResponse(token));
    }

    public ResponseEntity<?>createNewUsers(@RequestBody RegistrationUserDTO registrationUserDTO){
        if (!registrationUserDTO.getPassword().equals(registrationUserDTO.getMatchingPassword())){
            return new ResponseEntity<>(new AppError(HttpStatus.BAD_REQUEST.value(), "Пароли не совпадают"), HttpStatus.BAD_REQUEST);
        }
        if (userService.findByUserName(registrationUserDTO.getUsername()).isPresent()){
            return new ResponseEntity<>(new AppError(HttpStatus.BAD_REQUEST.value(), "Пользователь с данным именем уже существует"), HttpStatus.BAD_REQUEST);
        }
        User user = userService.create(registrationUserDTO);
        return ResponseEntity.ok(new RegistrationUserDTO(user.getId(), user.getUsername(), user.getPassword()));
    }

}
