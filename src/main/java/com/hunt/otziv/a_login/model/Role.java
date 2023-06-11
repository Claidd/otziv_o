package com.hunt.otziv.a_login.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;
//implements GrantedAuthority

public enum Role {

    ADMIN,
    CALLING,
    MANAGER,
    WORKER,
    CLIENT

//    @Override
//    public String getAuthority() {
//        return name();
//    }
}
