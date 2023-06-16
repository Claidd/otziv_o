package com.hunt.otziv.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/")
public class MainController {

    @GetMapping
    public String index(){
        return "index";
    }

    @GetMapping("/kvesty")
    public String kvesty(){
        return "kvesty";
    }

    @GetMapping("/lasertag")
    public String lasertag(){
        return "lasertag";
    }

    @GetMapping("/nerf")
    public String nerf(){
        return "nerf";
    }

    @GetMapping("/api/index")
    public String index2(){
        return "index";
    }

//    @GetMapping("/index2")
//    public String index2(){
//        return "index2";
//    }
}
