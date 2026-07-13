package com.hunt.otziv.manager_performance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ManagerPerformanceGradeTest {

    @ParameterizedTest
    @CsvSource({
            "100,A", "90,A",
            "89,B", "80,B",
            "79,C", "70,C",
            "69,D", "60,D",
            "59,E", "50,E",
            "49,F", "40,F",
            "39,G", "30,G",
            "29,H", "20,H",
            "19,I", "10,I",
            "9,J", "0,J",
            "101,A", "-1,J"
    })
    void mapsScoreToOneOfTenBands(int score, String expected) {
        assertEquals(expected, ManagerPerformanceGrade.of(score));
    }
}
