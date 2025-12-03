package com.system.morapack.schemas;

public enum PackageStatus {
    PENDING,      // No tiene vuelo asignado
    ASSIGNED,     // Asignado a vuelo, esperando despegue
    IN_TRANSIT,   // Vuelo en el aire (despegó)
    ARRIVED,      // Llegó al destino
    DELIVERED,    // Cliente recogió
    DELAYED       // Vuelo retrasado
}
