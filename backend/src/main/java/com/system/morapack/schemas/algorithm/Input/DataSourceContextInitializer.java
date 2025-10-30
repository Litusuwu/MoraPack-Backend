package com.system.morapack.schemas.algorithm.Input;

import com.system.morapack.config.Constants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Initializes the DataSourceFactory with Spring ApplicationContext.
 * 
 * This component ensures that the ALNS algorithm can access the database
 * via DatabaseInputDataSource when Constants.DATA_SOURCE_MODE is set to DATABASE.
 * 
 * Without this initialization, the algorithm falls back to FILE mode (reading from data/ directory)
 * which causes ID mismatches between algorithm results and database records.
 * 
 * CRITICAL FIX: This solves the "flight ID 2121 not found" error by ensuring
 * that ALNS uses real database IDs instead of temporary file-based IDs.
 */
@Component
public class DataSourceContextInitializer {

    @Autowired
    private ApplicationContext applicationContext;

    @PostConstruct
    public void initialize() {
        System.out.println("===========================================");
        System.out.println("INITIALIZING DATA SOURCE FACTORY");
        System.out.println("===========================================");
        
        DataSourceFactory.setSpringContext(applicationContext);
        
        System.out.println("[DataSourceContextInitializer] Spring context set successfully");
        System.out.println("[DataSourceContextInitializer] DATA_SOURCE_MODE: " + Constants.DATA_SOURCE_MODE);
        System.out.println("[DataSourceContextInitializer] Database mode available: " + 
                          DataSourceFactory.isDatabaseModeAvailable());
        System.out.println("===========================================\n");
    }
}

