package com.hunt.otziv.r_review.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/review")
public class ReviewController {

    //Открываем главную страницу
    @GetMapping
    public String index(){
        return "index";
    }
    //Открываем страницу
    @GetMapping("/kvesty")
    public String kvesty(){
        return "kvesty";
    }
    //Открываем страницу
    @GetMapping("/lasertag")
    public String lasertag(){
        return "lasertag";
    }
    //Открываем страницу
    @GetMapping("/nerf")
    public String nerf(){
        return "nerf";
    }
    //Открываем страницу
    @GetMapping("/api/index")
    public String index2(){
        return "index";
    }

//    @GetMapping("/index2")
//    public String index2(){
//        return "index2";
//    }
}
