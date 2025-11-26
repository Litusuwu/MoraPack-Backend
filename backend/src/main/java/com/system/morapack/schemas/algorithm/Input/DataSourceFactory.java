package com.system.morapack.schemas.algorithm.Input;

import com.system.morapack.config.Constants;
import org.springframework.context.ApplicationContext;

/**
 * Factory for creating InputDataSource implementations based on Constants.DATA_SOURCE_MODE.
 *
 * This factory allows the ALNS algorithm to seamlessly switch between:
 * - FILE mode: Reads from data/ directory (current implementation)
 * - DATABASE mode: Reads from PostgreSQL via Spring Data JPA
 *
 * Usage:
 * <pre>
 * // Without Spring context (FILE mode only)
 * InputDataSource dataSource = DataSourceFactory.createDataSource();
 *
 * // With Spring context (supports DATABASE mode)
 * InputDataSource dataSource = DataSourceFactory.createDataSource(applicationContext);
 * </pre>
 */
public class DataSourceFactory {

    private static ApplicationContext springContext;

    /**
     * Set the Spring ApplicationContext for DATABASE mode support.
     * This should be called once during application startup if DATABASE mode is needed.
     *
     * @param context Spring ApplicationContext
     */
    public static void setSpringContext(ApplicationContext context) {
        springContext = context;
    }

    /**
     * Creates an InputDataSource based on effective data source mode.
     * Checks DataSourceOverride first, then falls back to Constants.DATA_SOURCE_MODE.
     * Falls back to FILE mode if DATABASE mode is selected but Spring context is unavailable.
     *
     * @return InputDataSource implementation (FileInputDataSource or DatabaseInputDataSource)
     */
    public static InputDataSource createDataSource() {
        // Check for override first
        com.system.morapack.config.Constants.DataSourceMode effectiveMode = 
            com.system.morapack.config.DataSourceOverride.getEffectiveMode();
        
        if (com.system.morapack.config.DataSourceOverride.hasOverride()) {
            System.out.println("[FACTORY] Using OVERRIDDEN data source mode: " + effectiveMode);
        } else {
            System.out.println("[FACTORY] Creating data source for mode: " + effectiveMode);
        }

        switch (effectiveMode) {
            case FILE:
                System.out.println("[FACTORY] Using FILE-based data source (data/ directory)");
                return new FileInputDataSource();

            case DATABASE:
                if (springContext != null) {
                    System.out.println("[FACTORY] Using DATABASE-based data source (PostgreSQL)");
                    return springContext.getBean(DatabaseInputDataSource.class);
                } else {
                    System.err.println("[FACTORY] WARNING: DATABASE mode selected but Spring context not available!");
                    System.err.println("[FACTORY] Falling back to FILE mode");
                    return new FileInputDataSource();
                }

            default:
                System.err.println("[FACTORY] Unknown data source mode: " + effectiveMode);
                System.err.println("[FACTORY] Falling back to FILE mode");
                return new FileInputDataSource();
        }
    }

    /**
     * Creates an InputDataSource with explicit Spring context.
     * Use this method when calling from Spring-managed components.
     *
     * @param context Spring ApplicationContext
     * @return InputDataSource implementation
     */
    public static InputDataSource createDataSource(ApplicationContext context) {
        setSpringContext(context);
        return createDataSource();
    }

    /**
     * Checks if DATABASE mode is available (Spring context is set).
     *
     * @return true if DATABASE mode can be used
     */
    public static boolean isDatabaseModeAvailable() {
        return springContext != null;
    }

    /**
     * Clears the Spring context reference.
     * Useful for testing or resetting the factory state.
     */
    public static void clearSpringContext() {
        springContext = null;
    }
}
