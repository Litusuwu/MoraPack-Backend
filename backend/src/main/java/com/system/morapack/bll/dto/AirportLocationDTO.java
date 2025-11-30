package com.system.morapack.bll.dto;

import lombok.Data;

@Data
public class AirportLocationDTO {
    private String codeIATA;
    private CityDTO city;
    private Double latitude;
    private Double longitude;
}