# TODO - Frontend: Hooks y UI para Eventos Dinámicos de Simulación

## Objetivo
Implementar la interfaz de usuario y hooks para manejar eventos dinámicos durante la simulación (cancelación de vuelos, órdenes urgentes, replanificación).

---

## Tareas Pendientes

### 1. Hooks Personalizados (API Integration)

- [ ] **Crear hook `useFlightCancellation`**
  - Ubicación: `src/hooks/api/useFlightCancellation.ts`
  - Endpoint: `POST /api/flights/{id}/cancel`
  - Retorna: `{ success, message, flight, affectedProducts, requiresReplanning }`
  - Debe invalidar queries de vuelos después del éxito

- [ ] **Crear hook `useUrgentOrder`**
  - Ubicación: `src/hooks/api/useUrgentOrder.ts`
  - Endpoint: `POST /api/orders/urgent`
  - Retorna: `{ success, message, order, requiresReplanning }`
  - Debe invalidar queries de órdenes después del éxito

- [ ] **Crear hook `useReplanSimulation`**
  - Ubicación: `src/hooks/api/useReplanSimulation.ts`
  - Endpoint: `POST /api/algorithm/replan`
  - Retorna: `{ success, message, executionTimeMs, algorithmResult }`
  - Debe manejar estados de loading (puede tomar 30-90 segundos)

---

### 2. Componentes de UI

- [ ] **Agregar botón "Cancelar Vuelo" en FlightDetailsModal o mapa**
  - Mostrar solo si el vuelo puede ser cancelado (estado ACTIVE/SCHEDULED)
  - Mostrar confirmación antes de cancelar
  - Deshabilitar si vuelo está EN_VUELO o COMPLETADO

- [ ] **Agregar modal/formulario para crear órdenes urgentes**
  - Componente: `src/components/UrgentOrderModal.tsx`
  - Campos: nombre, ciudad destino, fecha entrega, cliente
  - Validar que fecha de entrega sea futura
  - Mostrar lista de ciudades disponibles

- [ ] **Agregar LoadingModal o spinner durante replanificación**
  - Componente: `src/components/ReplanningLoader.tsx`
  - Mostrar progreso/spinner
  - Mensaje: "Replanificando rutas..." con tiempo estimado
  - No permitir cerrar hasta completar

---

### 3. Lógica de Simulación

- [ ] **Implementar flujo: pausar → replanificar → actualizar timeline**
  - Modificar `useTemporalSimulation.ts` para soportar pausas
  - Al replanificar: guardar tiempo actual, pausar, esperar resultado
  - Actualizar `timeline` con nuevos eventos del backend
  - Opción: reiniciar desde tiempo 0 o continuar desde tiempo actual
  - Actualizar todos los componentes visuales (mapa, estadísticas)

---

## Flujo de Trabajo Esperado

### Escenario 1: Cancelar Vuelo
```
Usuario hace clic en "Cancelar Vuelo" 
  → Mostrar confirmación
  → useFlightCancellation() ejecuta
  → Si requiresReplanning === true
    → Pausar simulación
    → useReplanSimulation() ejecuta
    → Mostrar LoadingModal
    → Al completar: actualizar timeline
    → Reiniciar/continuar simulación
```

### Escenario 2: Agregar Orden Urgente
```
Usuario hace clic en "Nueva Orden Urgente"
  → Mostrar UrgentOrderModal
  → Usuario completa formulario
  → useUrgentOrder() ejecuta
  → Si requiresReplanning === true
    → Mismo flujo que cancelación
```

---

## Notas Importantes

- Los hooks deben usar `@tanstack/react-query` (ya configurado en el proyecto)
- Considerar agregar botones en `FlightMonitor.tsx` o `SimulationControls.tsx`
- La replanificación toma tiempo - importante mostrar feedback visual
- Después de replanificar, el `dataStore` debe actualizarse con nuevos resultados
- Invalidar queries relevantes: `flightKeys`, `orderKeys`, `algorithmKeys`

---

## Testing

- [ ] Probar cancelación de vuelo ACTIVE → debe funcionar
- [ ] Probar cancelación de vuelo COMPLETADO → debe fallar
- [ ] Probar crear orden urgente con fecha pasada → debe fallar
- [ ] Probar replanificación completa end-to-end
- [ ] Verificar que timeline se actualiza correctamente después de replan

---

**Prioridad:** Media-Alta  
**Estimación:** 8-12 horas de desarrollo  
**Dependencias:** Endpoints del backend ya implementados  
**Documentación:** Ver `MoraPack/backend/SIMULATION_EVENTS_API.md`

