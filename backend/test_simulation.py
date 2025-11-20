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
        "endTime": "2025-01-08T00:00:00"
    }
    try:
        resp = requests.post(f"{BASE_URL}/data/load-orders", params=params)
        if resp.status_code != 200:
            print(f"FAILED with status {resp.status_code}")
            print(f"Response body: {resp.text}")
        resp.raise_for_status()
        stats = resp.json().get('statistics', {})
        print(f"Orders loaded: {stats.get('ordersLoaded')}")
    except Exception as e:
        print(f"Error loading orders: {e}")

def main():
    print_header("STARTING SIMULATION FLOW TEST")

    # 0. Load Data
    load_initial_data()

    # 1. Get initial warehouse capacities
    print_header("1. Initial Warehouse Capacities")
    initial_warehouses = get_warehouses()
    initial_map = {w['id']: w for w in initial_warehouses}
    
    print(f"Found {len(initial_warehouses)} warehouses.")
    for w in initial_warehouses[:5]: # Print first 5
        print(f"ID: {w['id']}, Name: {w['name']}, Used: {w['usedCapacity']}/{w['maxCapacity']}")

    # 2. Run Algorithm for Day 1
    print_header("2. Running Algorithm (Day 1)")
    # Assuming data is already loaded for 2025-01-02
    run_result = run_algorithm("2025-01-02")
    
    if not run_result:
        print("Skipping rest of test due to algorithm failure.")
        return

    print(f"Algorithm Stats:")
    print(f"  Total Orders: {run_result.get('totalOrders')}")
    print(f"  Assigned Orders: {run_result.get('assignedOrders')}")
    print(f"  Total Products: {run_result.get('totalProducts')}")
    print(f"  Assigned Products: {run_result.get('assignedProducts')}")

    if run_result.get('assignedProducts', 0) == 0:
        print("WARNING: No products were assigned. Checking if orders exist...")
        # Optional: Add check for existing orders here
    
    # 3. Advance time to trigger movements
    print_header("3. Advancing Time & Updating States")

    # Get warehouse state after algorithm run (products assigned to flights)
    warehouses_after_algorithm = get_warehouses()
    warehouse_map = {w['id']: w for w in warehouses_after_algorithm}

    print(f"Main warehouses after algorithm:")
    for w in warehouses_after_algorithm:
        if 'Lima' in w['name'] or 'Baku' in w['name'] or 'Bruselas' in w['name']:
            print(f"  {w['name']}: {w['usedCapacity']}/{w['maxCapacity']}")

    # Advance by chunks to see progression
    start_time = datetime.strptime("2025-01-02T00:00:00", "%Y-%m-%dT%H:%M:%S")

    # Check every 4 hours for 48 hours (2 days)
    for i in range(1, 13):
        current_time = start_time + timedelta(hours=i*4)
        current_time_str = current_time.strftime("%Y-%m-%dT%H:%M:%S")
        result = update_states(current_time_str, warehouse_map)
        if result and result.get('transitions', {}).get('total', 0) > 0:
            print(f"!!! MOVEMENT DETECTED AT {current_time_str} !!!")

        # Update warehouse map for next iteration
        warehouses_after_update = get_warehouses()
        warehouse_map = {w['id']: w for w in warehouses_after_update}

    # 4. Get updated warehouse capacities
    print_header("4. Updated Warehouse Capacities")
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
        print("No warehouse capacity changes detected.")
    else:
        print("Warehouse capacity changes verified.")

if __name__ == "__main__":
    main()
