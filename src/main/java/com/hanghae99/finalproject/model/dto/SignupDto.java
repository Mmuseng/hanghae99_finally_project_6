package com.hanghae99.finalproject.model.dto;

import lombok.*;

@NoArgsConstructor
@AllArgsConstructor
@Setter
@Getter
public class SignupDto {
    private String username;
    private String nickname;
    private String password;
}