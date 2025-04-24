package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.l_lead.repository.TelephoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class TelephoneServiceImpl implements TelephoneService {

    private final TelephoneRepository telephoneRepository;


}
