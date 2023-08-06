package com.hunt.otziv.u_users.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class JwtTokenUtils {
    @Value("${jwt.secret}")
    private String secret;
    @Value("${jwt.lifetime}")
    private Duration jwtLifetime;

    //Метод генерации токенов
    public String generateToken(UserDetails userDetails){
        Map<String, Object> claims = new HashMap<>();
        List<String> roleList = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());
        claims.put("roles", roleList);

        //Текущее время
        Date issuedDate = new Date();
        //Время когда истечет токен. Прибавляя время через jwtLifetime заданный в yaml
        Date expiredDate = new Date(issuedDate.getTime() + jwtLifetime.toMillis());

        return Jwts.builder()
                //устанавливаем список ролей
                .setClaims(claims)
                //устанавливаем имя пользователя
                .setSubject(userDetails.getUsername())
                //устанавливаем текущее вермя
                .setIssuedAt(issuedDate)
                //устанавливаем время окончания действия токена
                .setExpiration(expiredDate)
                //устанавливаем секретный ключ закодированный при помощи алгоритма 256
                .signWith(SignatureAlgorithm.HS256, secret)
                .compact();
    }

    //Метод разбора токена на куски для проверки
    public Claims getAllClaimsFromToken(String token){
        return Jwts.parser()
                .setSigningKey(secret)
                .parseClaimsJwt(token)
                .getBody();
    }

    //Из токена получить имя пользователя
    public String getUsername(String token){
        return getAllClaimsFromToken(token).getSubject();
    }

    //Из токена получить роли
    public List<String> getRoles(String token){
        return getAllClaimsFromToken(token).get("roles", List.class);
    }


    //Библиотека - зависимость
//    <dependency>
//			<groupId>io.jsonwebtoken</groupId>
//			<artifactId>jjwt</artifactId>
//			<version>0.9.1</version>
//    </dependency>

}
