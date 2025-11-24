import requests
import json
import time
from datetime import datetime, timedelta

BASE_URL = "http://localhost:8080/api"

def print_header(msg):
    print("\n" + "="*50)
    print(msg)
    print("="*50)

def get_warehouses():
    try:
        response = requests.get(f"{BASE_URL}/warehouses")
        response.raise_for_status()
        return response.json()
    except Exception as e:
        print(f"Error fetching warehouses: {e}")
        return []

def run_algorithm(date_str):
    print(f"Running algorithm for {date_str}...")
    payload = {
        "simulationStartTime": f"{date_str}T00:00:00",
        "simulationDurationHours": 24,
        "useDatabase": True
    }
    try:
        response = requests.post(f"{BASE_URL}/algorithm/daily", json=payload)
        response.raise_for_status()
        print("Algorithm run successful.")
        return response.json()
    except Exception as e:
        print(f"Error running algorithm: {e}")
        if hasattr(e, 'response') and e.response:
            print(f"Response: {e.response.text}")
        return None

def update_states(current_time_str, initial_warehouse_map):
    print(f"Updating states for time: {current_time_str}...")
    payload = {
        "currentTime": current_time_str
    }
    try:
        response = requests.post(f"{BASE_URL}/simulation/update-states", json=payload)
        response.raise_for_status()
        result = response.json()
        print(f"Transitions: {json.dumps(result.get('transitions', {}), indent=2)}")

        # Get updated warehouses and show capacity changes
        updated_warehouses = get_warehouses()

        # Check for capacity changes
        changes = []
        for w in updated_warehouses:
            w_id = w['id']
            if w_id in initial_warehouse_map:
                initial_used = initial_warehouse_map[w_id]['usedCapacity']
                current_used = w['usedCapacity']
                if initial_used != current_used:
                    changes.append({
                        'id': w_id,
                        'name': w['name'],
                        'before': initial_used,
                        'after': current_used,
                        'delta': current_used - initial_used
                    })

        if changes:
            print(f"  Warehouse capacity changes detected:")
            for change in changes:
                delta_str = f"+{change['delta']}" if change['delta'] > 0 else str(change['delta'])
                print(f"    - {change['name']} (ID: {change['id']}): {change['before']} -> {change['after']} ({delta_str})")
        else:
            print(f"  No warehouse capacity changes at this time step.")

        return result
    except Exception as e:
        print(f"Error updating states: {e}")
        return None

def load_initial_data():
    print_header("0. Loading Initial Data")
    
    # 1. Load Airports
    print("Loading airports...")
    try:
        resp = requests.post(f"{BASE_URL}/data-import/airports")
        resp.raise_for_status()
        print(f"Airports loaded: {resp.json().get('count')}")
    except Exception as e:
        print(f"Error loading airports: {e}")

    # 2. Load Flights
    print("Loading flights...")
    try:
        resp = requests.post(f"{BASE_URL}/data-import/flights")
        resp.raise_for_status()
        print(f"Flights loaded: {resp.json().get('count')}")
    except Exception as e:
        print(f"Error loading flights: {e}")

    # 3. Load Orders
    print("Loading orders for 2025-01-02 to 2025-01-08...")
    params = {
        "startTime": "2025-01-02T00:00:00",
        "endTime": "2025-01-08T23:59:59"
    }
    try:
        resp = requests.post(f"{BASE_URL}/data/load-orders", params=params)
        if resp.status_code != 200:
            print(f"FAILED with status {resp.status_code}")
            print(f"Response body: {resp.text}")
        resp.raise_for_status()
        stats = resp.json().get('statistics', {})
        print(f"Orders loaded: {stats.get('ordersLoaded')}")
        products_loaded = stats.get('ordersFiltered', 0)
        print(f"Products loaded: {products_loaded}")
    except Exception as e:
        print(f"Error loading orders: {e}")

def main():
    print_header("STARTING WEEKLY SIMULATION FLOW TEST")

    # 0. Load Data
    load_initial_data()

    # 1. Get initial warehouse capacities
    print_header("1. Initial Warehouse Capacities")
    initial_warehouses = get_warehouses()
    initial_map = {w['id']: w for w in initial_warehouses}
    
    print(f"Found {len(initial_warehouses)} warehouses.")
    for w in initial_warehouses[:5]: # Print first 5
        print(f"ID: {w['id']}, Name: {w['name']}, Used: {w['usedCapacity']}/{w['maxCapacity']}")

    # 2. Configuración de semana
    WEEK_START_DATE_STR = "2025-01-02"   # coherente con load_initial_data()
    WEEK_START = datetime.strptime(WEEK_START_DATE_STR + "T00:00:00", "%Y-%m-%dT%H:%M:%S")

    DAYS = 7               # 7 días
    STEP_HOURS = 4         # cada 4 horas
    TOTAL_HOURS = 24 * DAYS

    print_header("2. Running Algorithm for a FULL WEEK")

    # estado para control de día y almacenes
    last_algorithm_day = -1
    warehouse_map = {w['id']: w for w in initial_warehouses}


    # Recorremos la semana en pasos de 4 horas
    for step in range(0, TOTAL_HOURS + 1, STEP_HOURS):
        current_time = WEEK_START + timedelta(hours=step)
        current_time_str = current_time.strftime("%Y-%m-%dT%H:%M:%S")

        # día relativo 0..6
        delta = current_time - WEEK_START
        day_number = delta.days

        if day_number >= DAYS:
            break

        # --- 2.a Ejecutar algoritmo una sola vez por día ---
        if day_number > last_algorithm_day:
            print_header(f"DAY {day_number + 1} – Running daily algorithm")
            
            # el algoritmo recibe solo la fecha (yyyy-mm-dd)
            run_result = run_algorithm(current_time.strftime("%Y-%m-%d"))
            if not run_result:
                print("Algorithm failure, stopping weekly test.")
                return

            print("Algorithm Stats:")
            print(f"  Total Orders: {run_result.get('totalOrders')}")
            print(f"  Assigned Orders: {run_result.get('assignedOrders')}")
            print(f"  Total Products: {run_result.get('totalProducts')}")
            print(f"  Assigned Products: {run_result.get('assignedProducts')}")

            if run_result.get('assignedProducts', 0) == 0:
                print("WARNING: No products were assigned on this day.")

            last_algorithm_day = day_number

            # Estado de almacenes justo después del algoritmo
            warehouses_after_algorithm = get_warehouses()
            warehouse_map = {w['id']: w for w in warehouses_after_algorithm}

            print("Main warehouses after algorithm:")
            for w in warehouses_after_algorithm:
                if 'Lima' in w['name'] or 'Baku' in w['name'] or 'Bruselas' in w['name']:
                    print(f"  {w['name']}: {w['usedCapacity']}/{w['maxCapacity']}")

            # en el instante 00:00 del día recién planificado puedes no llamar update-states
            if step == 0:
                continue

        # --- 3. Avanzar tiempo y actualizar estados ---
        print_header(f"3. Advancing Time & Updating States – {current_time_str}")
        result = update_states(current_time_str, warehouse_map)
        if result and result.get('transitions', {}).get('total', 0) > 0:
            print(f"!!! MOVEMENT DETECTED AT {current_time_str} !!!")

        # Actualizar mapa de almacenes para el siguiente tick
        warehouses_after_update = get_warehouses()
        warehouse_map = {w['id']: w for w in warehouses_after_update}

    # 4. Capacidades finales después de la semana
    print_header("4. Updated Warehouse Capacities AFTER 7 DAYS")
    updated_warehouses = get_warehouses()
    
    print(f"Found {len(updated_warehouses)} warehouses.")
    
    changes_detected = False
    for w in updated_warehouses:
        w_id = w['id']
        if w_id in initial_map:
            initial_used = initial_map[w_id]['usedCapacity']
            current_used = w['usedCapacity']
            if initial_used != current_used:
                print(f"Warehouse {w['name']} (ID: {w_id}) capacity changed: {initial_used} -> {current_used}")
                changes_detected = True
    
    if not changes_detected:
        print("No warehouse capacity changes detected across the week.")
    else:
        print("Warehouse capacity changes verified for the full week.")

if __name__ == "__main__":
    main()