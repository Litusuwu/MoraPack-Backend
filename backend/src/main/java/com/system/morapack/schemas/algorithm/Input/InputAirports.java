package com.system.morapack.schemas.algorithm.Input;

import com.system.morapack.schemas.*;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class InputAirports {

    private ArrayList<AirportSchema> airportSchemas;
    private final String filePath;

    public InputAirports(String filePath) {
        this.filePath = filePath;
        this.airportSchemas = new ArrayList<>();
    }

    public ArrayList<AirportSchema> readAirports() {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            Continent currentContinent = null;
            Map<String, CitySchema> cityMap = new HashMap<>();

            // Skip the first two lines (header)
            reader.readLine();
            reader.readLine();

            while ((line = reader.readLine()) != null) {
                
                // Skip empty lines
                if (line.trim().isEmpty()) {
                    continue;
                }
                
                // Check if this is a continent header line
                if (line.contains("America") || line.contains("Europa") || line.contains("Asia")) {
                    if (line.contains("America")) {
                        currentContinent = Continent.America;
                        System.out.println("Continent: " + currentContinent);
                    } else if (line.contains("Europa")) {
                        currentContinent = Continent.Europa;
                        System.out.println("Continent: " + currentContinent);
                    } else if (line.contains("Asia")) {
                        currentContinent = Continent.Asia;
                        System.out.println("Continent: " + currentContinent);
                    }
                    continue;
                }
                
                // Parse airport data
                String[] parts = line.trim().split("\\s+");

                if (parts.length >= 7) {
                    // Declare variables outside try block so they're accessible after
                    int id = 0;
                    String codeIATA = "";
                    String cityName = "";
                    String countryName = "";
                    String alias = "";
                    int timezone = 0;
                    double maxCapacity = 400.0;

                    try {
                        id = Integer.parseInt(parts[0]);
                        codeIATA = parts[1];

                        // Find timezone (starts with + or -)
                        int timezoneIndex = -1;
                        for (int i = 2; i < parts.length; i++) {
                            if (parts[i].startsWith("+") || parts[i].startsWith("-")) {
                                // Check if it's a number (timezone) not a city name part
                                try {
                                    Integer.parseInt(parts[i]);
                                    timezoneIndex = i;
                                    break;
                                } catch (NumberFormatException e) {
                                    // Not a timezone, continue searching
                                }
                            }
                        }

                        if (timezoneIndex == -1) {
                            System.err.println("Warning: Could not find timezone for " + codeIATA + ", skipping");
                            continue;
                        }

                        // Extract city name (from parts[2] to timezoneIndex-3)
                        // Format: CityName Country alias timezone
                        cityName = parts[2];

                        // Extract country (one position before alias)
                        countryName = parts[timezoneIndex - 2];

                        // Extract alias (one position before timezone)
                        alias = parts[timezoneIndex - 1];

                        // Extract timezone
                        timezone = Integer.parseInt(parts[timezoneIndex]);

                        // Extract capacity (one position after timezone)
                        int capacityIndex = timezoneIndex + 1;
                        maxCapacity = 400.0; // Default
                        if (capacityIndex < parts.length && !parts[capacityIndex].equals("Latitude:")) {
                            try {
                                maxCapacity = Double.parseDouble(parts[capacityIndex]);
                            } catch (NumberFormatException e) {
                                System.out.println("Warning: Could not parse capacity for " + codeIATA + ", using default 400.0");
                            }
                        }

                        System.out.println("Parsed: ID=" + id + ", IATA=" + codeIATA + ", City=" + cityName +
                                         ", Country=" + countryName + ", Alias=" + alias +
                                         ", TZ=" + timezone + ", Cap=" + maxCapacity);
                    } catch (Exception e) {
                        System.err.println("Error parsing line: " + line);
                        System.err.println("Error: " + e.getMessage());
                        continue;
                    }

                    // Extract latitude and longitude
                    String latitudeStr = "0.0";
                    String longitudeStr = "0.0";

                    // Find latitude and longitude in the line
                    int latIndex = line.indexOf("Latitude:");
                    int longIndex = line.indexOf("Longitude:");

                    if (latIndex != -1 && longIndex != -1) {
                        String latRaw = line.substring(latIndex + 10, longIndex).trim();
                        String longRaw = line.substring(longIndex + 11).trim();

                        // Convert DMS (Degrees Minutes Seconds) to decimal
                        latitudeStr = convertDMSToDecimal(latRaw);
                        longitudeStr = convertDMSToDecimal(longRaw);
                    }
                    
                    // Create CitySchema object if it doesn't exist
                    String cityKey = cityName + "-" + countryName;
                    CitySchema citySchema = cityMap.get(cityKey);
                    if (citySchema == null) {
                        citySchema = new CitySchema();
                        citySchema.setId(cityMap.size() + 1);
                        citySchema.setName(cityName);
                        citySchema.setCountry(countryName);
                        citySchema.setContinent(currentContinent);
                        cityMap.put(cityKey, citySchema);
                    }
                    
                    // Create Warehouse for the airportSchema
                    WarehouseSchema warehouse = new WarehouseSchema();
                    warehouse.setId(id);
                    warehouse.setMaxCapacity((int)maxCapacity);
                    warehouse.setUsedCapacity(0);
                    warehouse.setName(cityName + " Warehouse");
                    warehouse.setIsMainWarehouse(false);
                    
                    // Create AirportSchema object
                    AirportSchema airportSchema = new AirportSchema();
                    airportSchema.setId(id);
                    airportSchema.setCodeIATA(codeIATA);
                    airportSchema.setAlias(alias);
                    airportSchema.setTimezoneUTC(timezone);
                    airportSchema.setLatitude(latitudeStr);
                    airportSchema.setLongitude(longitudeStr);
                    airportSchema.setCitySchema(citySchema);
                    airportSchema.setState(AirportState.Avaiable);
                    airportSchema.setWarehouse(warehouse);
                    
                    // Set circular reference
                    warehouse.setAirportSchema(airportSchema);
                    
                    airportSchemas.add(airportSchema);
                }
            }
            
        } catch (IOException e) {
            System.err.println("Error reading airport data: " + e.getMessage());
            e.printStackTrace();
        }
        
        return airportSchemas;
    }

    /**
     * Convert DMS (Degrees Minutes Seconds) format to decimal degrees
     * Example input: "04° 42' 05\" N" or "74° 08' 49\" W"
     * Example output: "4.7014" or "-74.1469"
     */
    private String convertDMSToDecimal(String dmsString) {
        try {
            // Clean the string and split by spaces
            String cleaned = dmsString.replaceAll("[°'\"]+", " ").trim();
            String[] parts = cleaned.split("\\s+");

            if (parts.length < 3) {
                System.err.println("Warning: Invalid DMS format: " + dmsString);
                return "0.0";
            }

            // Parse degrees, minutes, seconds
            double degrees = Double.parseDouble(parts[0]);
            double minutes = parts.length > 1 ? Double.parseDouble(parts[1]) : 0.0;
            double seconds = parts.length > 2 ? Double.parseDouble(parts[2]) : 0.0;

            // Convert to decimal
            double decimal = degrees + (minutes / 60.0) + (seconds / 3600.0);

            // Check if South or West (negative)
            String direction = parts.length > 3 ? parts[3] : "";
            if (direction.equals("S") || direction.equals("W")) {
                decimal = -decimal;
            }

            // Format to 4 decimal places
            return String.format("%.4f", decimal);

        } catch (Exception e) {
            System.err.println("Error parsing DMS coordinates: " + dmsString + " - " + e.getMessage());
            return "0.0";
        }
    }
}
