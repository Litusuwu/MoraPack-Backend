package com.system.morapack.schemas.algorithm.Input;

import com.system.morapack.schemas.AirportSchema;
import com.system.morapack.schemas.FlightSchema;
import com.system.morapack.config.Constants;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.time.LocalTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class InputData {
    private ArrayList<FlightSchema> flightSchemas;
    private final String filePath;
    private ArrayList<AirportSchema> airportSchemas;

    public InputData(String filePath, ArrayList<AirportSchema> airportSchemas) {
        this.filePath = filePath;
        this.flightSchemas = new ArrayList<>();
        this.airportSchemas = airportSchemas;
    }

    public ArrayList<FlightSchema> readFlights() {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            int flightId = 1;
            Map<String, AirportSchema> airportMap = createAirportMap();
            
            while ((line = reader.readLine()) != null) {
                // Skip empty lines
                if (line.trim().isEmpty()) {
                    continue;
                }
                
                // Parse flight data
                // Format: ORIGIN-DESTINATION-DEPARTURE-ARRIVAL-CAPACITY
                String[] parts = line.split("-");
                if (parts.length == 5) {
                    String originCode = parts[0];
                    String destinationCode = parts[1];
                    String departureTime = parts[2];
                    String arrivalTime = parts[3];
                    int maxCapacity = Integer.parseInt(parts[4]);
                    
                    // Find airportSchemas by IATA code
                    AirportSchema originAirportSchema = airportMap.get(originCode);
                    AirportSchema destinationAirportSchema = airportMap.get(destinationCode);
                    
                    if (originAirportSchema != null && destinationAirportSchema != null) {
                        // Parse departure and arrival times
                        LocalTime departureLocalTime = parseTime(departureTime);
                        LocalTime arrivalLocalTime = parseTime(arrivalTime);

                        // Calculate transport time in hours
                        double transportTime = calculateTransportTime(departureTime, arrivalTime);

                        // Calculate cost (this is a placeholder - you might want to implement a more sophisticated cost model)
                        double cost = calculateFlightCost(originAirportSchema, destinationAirportSchema, maxCapacity);

                        // Create FlightSchema object
                        FlightSchema flightSchema = new FlightSchema();
                        flightSchema.setId(flightId++);
                        flightSchema.setFrequencyPerDay(1.0); // Default frequency
                        flightSchema.setOriginAirportSchema(originAirportSchema);
                        flightSchema.setDestinationAirportSchema(destinationAirportSchema);
                        flightSchema.setMaxCapacity(maxCapacity);
                        flightSchema.setUsedCapacity(0);
                        flightSchema.setTransportTime(transportTime);
                        flightSchema.setCost(cost);

                        // Store the actual departure and arrival times from flights.txt
                        flightSchema.setDepartureTime(departureLocalTime);
                        flightSchema.setArrivalTime(arrivalLocalTime);

                        flightSchemas.add(flightSchema);
                    }
                }
            }
            
        } catch (IOException e) {
            System.err.println("Error reading flight data: " + e.getMessage());
            e.printStackTrace();
        }
        
        return flightSchemas;
    }
    
    private Map<String, AirportSchema> createAirportMap() {
        Map<String, AirportSchema> map = new HashMap<>();
        for (AirportSchema airportSchema : airportSchemas) {
            map.put(airportSchema.getCodeIATA(), airportSchema);
        }
        return map;
    }
    
    private double calculateTransportTime(String departureTime, String arrivalTime) {
        LocalTime departure = parseTime(departureTime);
        LocalTime arrival = parseTime(arrivalTime);
        
        // Calculate duration between departure and arrival
        long minutes;
        if (arrival.isBefore(departure)) {
            // FlightSchema crosses midnight
            minutes = Duration.between(departure, LocalTime.of(23, 59, 59)).toMinutes() + 
                     Duration.between(LocalTime.of(0, 0), arrival).toMinutes() + 1;
        } else {
            minutes = Duration.between(departure, arrival).toMinutes();
        }
        
        // Convert minutes to hours
        return minutes / 60.0;
    }
    
    private LocalTime parseTime(String timeStr) {
        int hours = Integer.parseInt(timeStr.substring(0, 2));
        int minutes = Integer.parseInt(timeStr.substring(3, 5));
        return LocalTime.of(hours, minutes);
    }
    
    private double calculateFlightCost(AirportSchema origin, AirportSchema destination, int capacity) {
        // Simple cost model based on whether airportSchemas are in the same continent and capacity
        boolean sameContinentFlight = origin.getCitySchema().getContinent() == destination.getCitySchema().getContinent();
        
        double baseCost;
        if (sameContinentFlight) {
            baseCost = Constants.SAME_CONTINENT_TRANSPORT_TIME * 100;
        } else {
            baseCost = Constants.DIFFERENT_CONTINENT_TRANSPORT_TIME * 150;
        }
        
        // Adjust cost based on capacity
        double capacityFactor = capacity / 300.0;
        
        return baseCost * capacityFactor;
    }
}
