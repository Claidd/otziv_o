package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Filial;

public interface FilialService {
    Filial save(FilialDTO filialDTO);

    Filial save(Filial filial2);

    Filial getFilial(Long id);

    void deleteFilial(Long filialId);
    Filial findFilialByTitleAndUrl(String title, String url);

    FilialDTO getFilialByIdToDTO(Long id);
    void updateFilial(FilialDTO filialDTO);
}
