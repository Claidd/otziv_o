package com.hunt.otziv.p_products.services;

import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.services.service.AllOrderListService;
import com.hunt.otziv.p_products.services.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class AllOrderListServiceImpl implements AllOrderListService {

    private final CompanyService companyService;
    private final OrderService orderService;

}
