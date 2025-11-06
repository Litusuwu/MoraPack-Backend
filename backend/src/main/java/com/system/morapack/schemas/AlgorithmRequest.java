package com.system.morapack.schemas;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmRequest {
    private String algorithmType; // "ALNS" or "TABU"
    private Integer maxIterations;
    private Integer maxNoImprovement;
    private Integer neighborhoodSize;
    private Integer tabuListSize;
    private Long tabuTenure;
    private Boolean useDatabase; // true = use database data, false = use file data
}
