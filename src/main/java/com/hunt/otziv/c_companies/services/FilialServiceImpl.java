package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.repository.FilialRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class FilialServiceImpl implements FilialService{

    private final FilialRepository filialRepository;

    public FilialServiceImpl(FilialRepository filialRepository) {
        this.filialRepository = filialRepository;
    }

    public Filial save(FilialDTO filialDTO){
        Filial filial = new Filial();
        filial.setTitle(filialDTO.getTitle());
        filial.setUrl(filialDTO.getUrl());
        return filialRepository.save(filial);
    }

    public Filial save(Filial filial2){

        return filialRepository.save(filial2);
    }

    public Filial getFilial(Long id){
        return filialRepository.findById(id).orElse(null);
    }


    public Filial findFilialByTitleAndUrl(String title, String url) {
        return filialRepository.findByTitleAndUrl(title, url);
    }
}
