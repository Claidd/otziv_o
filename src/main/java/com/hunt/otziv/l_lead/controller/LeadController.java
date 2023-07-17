package com.hunt.otziv.l_lead.controller;


import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;
import java.util.Map;

@Controller
@Slf4j
@RequestMapping("/lead")
public class LeadController {

    @GetMapping()
    public ModelAndView lead(final Map<String, Object> model){
//        model.put("route", "lead");
        return new ModelAndView("lead/layouts/lead", model);
    }






}
