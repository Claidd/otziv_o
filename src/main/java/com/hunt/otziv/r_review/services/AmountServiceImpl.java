package com.hunt.otziv.r_review.services;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.r_review.dto.AmountDTO;
import com.hunt.otziv.r_review.model.Amount;
import com.hunt.otziv.r_review.repository.AmountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class AmountServiceImpl implements AmountService{

    private final AmountRepository amountRepository;

    public List<AmountDTO> getAmountDTOList(){
        List<Amount> amountDTOList = (List<Amount>) amountRepository.findAll();
        return amountDTOList.stream()
                .map(this::convertToDTO).collect(Collectors.toList());
    }

    private AmountDTO convertToDTO(Amount amount) {
        AmountDTO amountDTO = new AmountDTO();
        amountDTO.setAmount(amount.getAmount());
        return amountDTO;
    }
}
