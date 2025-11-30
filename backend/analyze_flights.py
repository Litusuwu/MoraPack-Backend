#!/usr/bin/env python3
"""
Script para analizar vuelos en un rango de tiempo específico.
Formato del archivo: ORIGEN-DESTINO-HORA_INICIO-HORA_FIN-CODIGO
"""

import sys
from datetime import time
from collections import defaultdict

def parse_time(time_str):
    """Convierte HH:MM a objeto time"""
    hour, minute = map(int, time_str.split(':'))
    return time(hour, minute)

def filter_flights_by_departure_time(file_path, start_hour, end_hour):
    """
    Filtra vuelos cuya hora de salida esté entre start_hour y end_hour.

    Args:
        file_path: Ruta al archivo flights.txt
        start_hour: Hora de inicio (formato HH:MM o solo HH)
        end_hour: Hora de fin (formato HH:MM o solo HH)

    Returns:
        Lista de vuelos que cumplen el criterio
    """
    # Parsear horas de inicio y fin
    if ':' not in start_hour:
        start_hour = f"{start_hour}:00"
    if ':' not in end_hour:
        end_hour = f"{end_hour}:00"

    start_time = parse_time(start_hour)
    end_time = parse_time(end_hour)

    matching_flights = []

    with open(file_path, 'r') as f:
        for line_num, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue

            parts = line.split('-')
            if len(parts) != 5:
                print(f"Advertencia: Línea {line_num} tiene formato incorrecto: {line}", file=sys.stderr)
                continue

            origin, destination, departure_time, arrival_time, code = parts

            try:
                dep_time = parse_time(departure_time)

                # Verificar si está en el rango
                if start_time <= dep_time < end_time:
                    matching_flights.append({
                        'line_num': line_num,
                        'origin': origin,
                        'destination': destination,
                        'departure': departure_time,
                        'arrival': arrival_time,
                        'code': code,
                        'raw': line
                    })
            except ValueError as e:
                print(f"Error parseando línea {line_num}: {e}", file=sys.stderr)
                continue

    return matching_flights

def analyze_flights_by_hour(file_path):
    """
    Agrupa vuelos por hora de salida para análisis.

    Returns:
        Diccionario con hora -> cantidad de vuelos
    """
    flights_by_hour = defaultdict(int)

    with open(file_path, 'r') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue

            parts = line.split('-')
            if len(parts) != 5:
                continue

            departure_time = parts[2]
            hour = int(departure_time.split(':')[0])
            flights_by_hour[hour] += 1

    return dict(sorted(flights_by_hour.items()))

def main():
    if len(sys.argv) < 2:
        print("Uso:")
        print("  python3 analyze_flights.py <hora_inicio> <hora_fin>")
        print("  python3 analyze_flights.py analyze")
        print()
        print("Ejemplos:")
        print("  python3 analyze_flights.py 03:00 10:00")
        print("  python3 analyze_flights.py 3 10")
        print("  python3 analyze_flights.py analyze")
        sys.exit(1)

    file_path = "backend/data/flights.txt"

    if sys.argv[1] == "analyze":
        # Mostrar distribución por hora
        print("Distribución de vuelos por hora de salida:")
        print("=" * 50)
        flights_by_hour = analyze_flights_by_hour(file_path)

        for hour in range(24):
            count = flights_by_hour.get(hour, 0)
            bar = '█' * (count // 10)
            print(f"{hour:02d}:00 - {hour:02d}:59 | {count:4d} vuelos | {bar}")

        total = sum(flights_by_hour.values())
        print("=" * 50)
        print(f"Total: {total} vuelos")

    else:
        if len(sys.argv) < 3:
            print("Error: Debes proporcionar hora de inicio y fin")
            sys.exit(1)

        start_hour = sys.argv[1]
        end_hour = sys.argv[2]

        flights = filter_flights_by_departure_time(file_path, start_hour, end_hour)

        print(f"Vuelos que salen entre {start_hour} y {end_hour}:")
        print("=" * 80)

        # Agrupar por ruta
        routes = defaultdict(list)
        for flight in flights:
            route = f"{flight['origin']} → {flight['destination']}"
            routes[route].append(flight)

        for route, route_flights in sorted(routes.items()):
            print(f"\n{route} ({len(route_flights)} vuelos):")
            for flight in route_flights:
                print(f"  {flight['departure']} - {flight['arrival']} (Código: {flight['code']})")

        print("\n" + "=" * 80)
        print(f"Total: {len(flights)} vuelos encontrados")

if __name__ == "__main__":
    main()
