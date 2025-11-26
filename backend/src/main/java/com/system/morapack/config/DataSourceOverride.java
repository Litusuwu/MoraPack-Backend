package com.system.morapack.config;

/**
 * Thread-safe override for data source mode.
 * 
 * This allows temporary switching to FILE mode for collapse scenarios
 * without modifying the final constant in Constants.java (which fails in Java 9+).
 * 
 * Usage:
 * <pre>
 *   DataSourceOverride.setOverride(DataSourceMode.FILE);
 *   try {
 *     // ... code that needs FILE mode ...
 *   } finally {
 *     DataSourceOverride.clearOverride();
 *   }
 * </pre>
 */
public class DataSourceOverride {
    
    private static final ThreadLocal<Constants.DataSourceMode> override = new ThreadLocal<>();
    
    /**
     * Set a temporary override for the current thread
     */
    public static void setOverride(Constants.DataSourceMode mode) {
        override.set(mode);
        System.out.println("[DataSourceOverride] Set override to: " + mode);
    }
    
    /**
     * Clear the override for the current thread
     */
    public static void clearOverride() {
        Constants.DataSourceMode previous = override.get();
        override.remove();
        if (previous != null) {
            System.out.println("[DataSourceOverride] Cleared override (was: " + previous + ")");
        }
    }
    
    /**
     * Get the effective data source mode (override takes precedence)
     */
    public static Constants.DataSourceMode getEffectiveMode() {
        Constants.DataSourceMode overrideMode = override.get();
        if (overrideMode != null) {
            return overrideMode;
        }
        return Constants.DATA_SOURCE_MODE;
    }
    
    /**
     * Check if an override is currently active
     */
    public static boolean hasOverride() {
        return override.get() != null;
    }
}


