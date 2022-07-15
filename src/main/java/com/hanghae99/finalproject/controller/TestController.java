package com.hanghae99.finalproject.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

// test용 controller
@Controller
@RequiredArgsConstructor
public class TestController {
    @GetMapping("/test")
    public String test() {
        String testStr = "Hello World";
        System.out.println(testStr);
        return testStr;
    }

    @ResponseBody
    @GetMapping("/")
    public String tes2t() {
        return "whitewise?";
    }
}
