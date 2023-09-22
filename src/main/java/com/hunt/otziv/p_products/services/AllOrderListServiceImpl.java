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

//    @Override
//    public List<OrderDTO> getCompaniesAllStatusByIdAndKeyword(String keywords) {
//        if (!keywords.isEmpty()){
//            log.info("Отработал метод с keywords");
//            List<OrderDTO> orderDTOList = orderService.;
//            if (company != null){
//                log.info("Отработал не равно null " + company.getId());
//                return convertToDto(company);
//            }
//            else {
//                log.info("Отработал равно null");
//                return convertToDto(companyRepository.findById(companyId).orElseThrow(() -> new UsernameNotFoundException(
//                        String.format("Компания '%d' не найден", companyId))));
//            }
//
//        }
//        else return convertToDto(companyRepository.findById(companyId).orElseThrow(() -> new UsernameNotFoundException(
//                String.format("Компания '%d' не найден", companyId))));
//    }
    // берем компанию с поиском для вывода всех ее заказов
}
