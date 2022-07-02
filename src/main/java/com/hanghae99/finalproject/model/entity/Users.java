package com.hanghae99.finalproject.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hanghae99.finalproject.model.dto.SocialLoginRequestDto;
import com.hanghae99.finalproject.model.dto.UserRequestDto;
import lombok.*;

import javax.persistence.*;

import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class Users {

    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    private Long id;
    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String nickname;

    @Column(nullable = true)
    private String imgPath;

    @JsonIgnore
    @Column(nullable = true)
    private String password;

    public Users(String username, String nickname, String password) {
        this.username = username;
        this.nickname = nickname;
        this.password = password;
    }

    public Users(SocialLoginRequestDto socialLoginRequestDto, int allCount) {
        this.username = socialLoginRequestDto.getEmail();
        this.nickname = "USER(" + UUID.randomUUID().toString().replaceAll("-", "").substring(5, 9) + allCount + ")";
    }

    public void update(UserRequestDto userRequestDto) {
        this.nickname = userRequestDto.getNickname();
        this.imgPath = userRequestDto.getImgPath();
    }

    public void updatePw(UserRequestDto userRequestDto) {
        this.password = userRequestDto.getPassword();
    }
}
