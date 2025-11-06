package com.system.morapack.config;

public class Constants {
    // File paths
    public static final String AIRPORT_INFO_FILE_PATH = "data/airportInfo.txt";
    public static final String FLIGHTS_FILE_PATH = "data/flights.txt";
    public static final String PRODUCTS_FILE_PATH = "data/products.txt";
    public static final String ORDER_FILES_DIRECTORY = "data/products";  // Directory containing _pedidos_{AIRPORT}_.txt files
    
    // Algorithm constants

    public static final int LOWERBOUND_SOLUTION_SPACE = 100;
    public static final int UPPERBOUND_SOLUTION_SPACE = 200;
    
    // ALNS Destruction parameters optimized for MoraPack
    public static final double DESTRUCTION_RATIO = 0.15;        // 15% - Ratio moderado para ALNS
    public static final int DESTRUCTION_MIN_PACKAGES = 10;      // Mínimo 10 paquetes
    public static final int DESTRUCTION_MAX_PACKAGES = 500;     // Máximo 500 paquetes (ajustable según problema)
    public static final int DESTRUCTION_MAX_PACKAGES_EXPANSION = 100;  // Para expansiones más controladas
    
    // Delivery time constants
    public static final double SAME_CONTINENT_MAX_DELIVERY_TIME = 2.0;
    public static final double DIFFERENT_CONTINENT_MAX_DELIVERY_TIME = 3.0;
    
    public static final double SAME_CONTINENT_TRANSPORT_TIME = 0.5;
    public static final double DIFFERENT_CONTINENT_TRANSPORT_TIME = 1.0;
    
    public static final int SAME_CONTINENT_MIN_CAPACITY = 200;
    public static final int SAME_CONTINENT_MAX_CAPACITY = 300;
    public static final int DIFFERENT_CONTINENT_MIN_CAPACITY = 250;
    public static final int DIFFERENT_CONTINENT_MAX_CAPACITY = 400;
    
    public static final int WAREHOUSE_MIN_CAPACITY = 600;
    public static final int WAREHOUSE_MAX_CAPACITY = 1000;
    
    public static final int CUSTOMER_PICKUP_MAX_HOURS = 2;
    
    // NEW: Control de tipo de solución inicial
    public static final boolean USE_GREEDY_INITIAL_SOLUTION = true; // true=greedy, false=random
    public static final double RANDOM_ASSIGNMENT_PROBABILITY = 0.3; // Para solución random: 30% de asignación
    
    // Control de logs
    public static final boolean VERBOSE_LOGGING = false; // true=logs detallados, false=logs mínimos
    public static final int LOG_ITERATION_INTERVAL = 100; // Mostrar solo cada X iteraciones
    
    // Diversificación extrema / Restart inteligente
    public static final int STAGNATION_THRESHOLD_FOR_RESTART = 50; // Iteraciones sin mejora significativa para restart
    public static final double SIGNIFICANT_IMPROVEMENT_THRESHOLD = 0.1; // 0.1% mínimo para considerar mejora significativa
    public static final double EXTREME_DESTRUCTION_RATIO = 0.8; // 80% destrucción para restart
    public static final int MAX_RESTARTS = 3; // Máximo número de restarts por ejecución

    public static final String LIMA_WAREHOUSE = "Lima, Peru";
    public static final String BRUSSELS_WAREHOUSE = "Brussels, Belgium";
    public static final String BAKU_WAREHOUSE = "Baku, Azerbaijan";
    
    // Tabu Search parameters
    public static final int TABU_TENURE = 50;
    public static final int MAX_ITERATIONS_TABU = 1000;
    public static final int STAGNATION_THRESHOLD_TABU = 100;
    public static final int DIVERSIFICATION_THRESHOLD_TABU = 50;
    public static final int INTENSIFICATION_THRESHOLD_TABU = 20;
    public static final double DIVERSIFICATION_FACTOR_TABU = 0.3;
    public static final double INTENSIFICATION_FACTOR_TABU = 0.1;
    public static final int MAX_DIVERSIFICATIONS_TABU = 10;
    public static final int MAX_INTENSIFICATIONS_TABU = 5;
    public static final double NEIGHBORHOOD_SIZE_FACTOR = 0.1;
    public static final double ASPIRATION_THRESHOLD_PERCENTAGE = 0.05;
    
    // ProductSchema unitization - Feature toggle
    public static final boolean ENABLE_PRODUCT_UNITIZATION = true;
    
    // Temporal validation constants
    public static final int HORIZON_DAYS = 4; // 4 días de horizonte temporal
    public static final int MIN_LAYOVER_TIME_MINUTES = 60; // CRITICAL: Minimum 1 hour layover at intermediate stops
    public static final int CONNECTION_TIME_MINUTES = 120; // 2 horas de conexión (includes minimum layover)
    public static final int PRE_FLIGHT_PROCESSING_MINUTES = 120; // 2 horas antes del vuelo
    
    // MoraPack headquarters validation
    public static final boolean VALIDATE_HEADQUARTERS_ORIGIN = true;

    // ========== DATA SOURCE CONFIGURATION ==========
    /**
     * Defines where the algorithm gets its input data from:
     * - FILE: Reads from data/ directory (airportInfo.txt, flights.txt, products.txt)
     * - DATABASE: Reads from PostgreSQL database via repositories
     *
     * Change this constant to switch between file-based and database-based input.
     */
    public static final DataSourceMode DATA_SOURCE_MODE = DataSourceMode.DATABASE;

    /**
     * Enum for data source selection
     */
    public enum DataSourceMode {
        FILE,       // Read from data/ directory files (current implementation)
        DATABASE    // Read from PostgreSQL database via Spring Data JPA
    }
}
