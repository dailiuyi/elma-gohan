#!/usr/bin/env python3
"""Build a compact prefecture lookup from the pinned level-2 GeoJSON source."""

from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import json
import math
import os
from pathlib import Path
import tempfile
from typing import Any, Iterable, Sequence


DEFAULT_OUTPUT = Path(__file__).resolve().parent / "data" / "china-prefecture-grid.json"


def _out_of_china(longitude: float, latitude: float) -> bool:
    return longitude < 72.004 or longitude > 137.8347 or latitude < 0.8293 or latitude > 55.8271


def _transform_latitude(x: float, y: float) -> float:
    result = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
    result += (20 * math.sin(6 * x * math.pi) + 20 * math.sin(2 * x * math.pi)) * 2 / 3
    result += (20 * math.sin(y * math.pi) + 40 * math.sin(y / 3 * math.pi)) * 2 / 3
    result += (160 * math.sin(y / 12 * math.pi) + 320 * math.sin(y * math.pi / 30)) * 2 / 3
    return result


def _transform_longitude(x: float, y: float) -> float:
    result = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
    result += (20 * math.sin(6 * x * math.pi) + 20 * math.sin(2 * x * math.pi)) * 2 / 3
    result += (20 * math.sin(x * math.pi) + 40 * math.sin(x / 3 * math.pi)) * 2 / 3
    result += (150 * math.sin(x / 12 * math.pi) + 300 * math.sin(x / 30 * math.pi)) * 2 / 3
    return result


def gcj02_to_wgs84(longitude: float, latitude: float) -> tuple[float, float]:
    if _out_of_china(longitude, latitude):
        return longitude, latitude
    earth_radius = 6378245.0
    eccentricity = 0.006693421622965943
    delta_latitude = _transform_latitude(longitude - 105, latitude - 35)
    delta_longitude = _transform_longitude(longitude - 105, latitude - 35)
    radians = latitude / 180 * math.pi
    magic = 1 - eccentricity * math.sin(radians) ** 2
    square_root = math.sqrt(magic)
    delta_latitude = delta_latitude * 180 / (
        (earth_radius * (1 - eccentricity)) / (magic * square_root) * math.pi
    )
    delta_longitude = delta_longitude * 180 / (
        earth_radius / square_root * math.cos(radians) * math.pi
    )
    return longitude - delta_longitude, latitude - delta_latitude


def _rings(geometry: dict[str, Any]) -> Iterable[Sequence[Sequence[float]]]:
    if geometry.get("type") == "Polygon":
        yield from geometry.get("coordinates", [])
    elif geometry.get("type") == "MultiPolygon":
        for polygon in geometry.get("coordinates", []):
            yield from polygon


def _coordinate_pairs(geometry: dict[str, Any]) -> Iterable[Sequence[float]]:
    for ring in _rings(geometry):
        yield from ring


def _bounds(geometry: dict[str, Any]) -> tuple[float, float, float, float]:
    coordinates = list(_coordinate_pairs(geometry))
    if not coordinates:
        raise ValueError("geometry contains no coordinates")
    longitudes = [float(point[0]) for point in coordinates]
    latitudes = [float(point[1]) for point in coordinates]
    return min(longitudes), min(latitudes), max(longitudes), max(latitudes)


def _point_on_segment(
    longitude: float,
    latitude: float,
    first: Sequence[float],
    second: Sequence[float],
) -> bool:
    x1, y1 = float(first[0]), float(first[1])
    x2, y2 = float(second[0]), float(second[1])
    cross = (longitude - x1) * (y2 - y1) - (latitude - y1) * (x2 - x1)
    if abs(cross) > 1e-9:
        return False
    return min(x1, x2) - 1e-9 <= longitude <= max(x1, x2) + 1e-9 and min(
        y1, y2
    ) - 1e-9 <= latitude <= max(y1, y2) + 1e-9


def _point_in_ring(longitude: float, latitude: float, ring: Sequence[Sequence[float]]) -> bool:
    inside = False
    previous = ring[-1]
    for current in ring:
        if _point_on_segment(longitude, latitude, previous, current):
            return True
        x1, y1 = float(previous[0]), float(previous[1])
        x2, y2 = float(current[0]), float(current[1])
        if (y1 > latitude) != (y2 > latitude):
            crossing = (x2 - x1) * (latitude - y1) / (y2 - y1) + x1
            if longitude < crossing:
                inside = not inside
        previous = current
    return inside


def _point_in_geometry(longitude: float, latitude: float, geometry: dict[str, Any]) -> bool:
    polygons = (
        [geometry.get("coordinates", [])]
        if geometry.get("type") == "Polygon"
        else geometry.get("coordinates", [])
    )
    for polygon in polygons:
        if not polygon or not _point_in_ring(longitude, latitude, polygon[0]):
            continue
        if any(_point_in_ring(longitude, latitude, hole) for hole in polygon[1:]):
            continue
        return True
    return False


def build_index(source: Path, source_commit: str) -> dict[str, Any]:
    source_bytes = source.read_bytes()
    raw = json.loads(source_bytes.decode("utf-8"))
    cities: list[dict[str, Any]] = []
    for feature in raw.get("features", []):
        properties = feature.get("properties") or {}
        geometry = feature.get("geometry") or {}
        if geometry.get("type") not in {"Polygon", "MultiPolygon"}:
            continue
        if not properties.get("full_name") or not properties.get("gb"):
            continue
        bounds = _bounds(geometry)
        cities.append(
            {
                "code": str(properties["gb"]),
                "label": str(properties["full_name"]),
                "province": str(properties.get("province") or properties["full_name"]),
                "geometry": geometry,
                "bounds": bounds,
                "bounds_area": (bounds[2] - bounds[0]) * (bounds[3] - bounds[1]),
            }
        )

    assignments: dict[tuple[int, int], str] = {}
    assigned_coordinates: dict[str, list[tuple[float, float]]] = defaultdict(list)
    for city in sorted(cities, key=lambda item: (item["bounds_area"], item["code"])):
        minimum_longitude, minimum_latitude, maximum_longitude, maximum_latitude = city["bounds"]
        for latitude_key in range(math.floor((minimum_latitude - 0.03) * 10), math.ceil((maximum_latitude + 0.03) * 10) + 1):
            for longitude_key in range(math.floor((minimum_longitude - 0.03) * 10), math.ceil((maximum_longitude + 0.03) * 10) + 1):
                key = (latitude_key, longitude_key)
                if key in assignments:
                    continue
                longitude, latitude = gcj02_to_wgs84(longitude_key / 10, latitude_key / 10)
                if not (
                    minimum_longitude <= longitude <= maximum_longitude
                    and minimum_latitude <= latitude <= maximum_latitude
                ):
                    continue
                if _point_in_geometry(longitude, latitude, city["geometry"]):
                    assignments[key] = city["code"]
                    assigned_coordinates[city["code"]].append((longitude, latitude))

    indexed_cities: list[dict[str, Any]] = []
    city_positions: dict[str, int] = {}
    for city in sorted(cities, key=lambda item: item["code"]):
        coordinates = assigned_coordinates.get(city["code"], [])
        if not coordinates:
            continue
        city_positions[city["code"]] = len(indexed_cities)
        indexed_cities.append(
            {
                "code": city["code"],
                "label": city["label"],
                "province": city["province"],
                "longitude": round(sum(point[0] for point in coordinates) / len(coordinates), 5),
                "latitude": round(sum(point[1] for point in coordinates) / len(coordinates), 5),
            }
        )

    grouped: dict[int, list[tuple[int, int]]] = defaultdict(list)
    for (latitude_key, longitude_key), city_code in assignments.items():
        grouped[latitude_key].append((longitude_key, city_positions[city_code]))

    rows: dict[str, list[list[int]]] = {}
    for latitude_key, values in sorted(grouped.items()):
        segments: list[list[int]] = []
        for longitude_key, city_index in sorted(values):
            if segments and segments[-1][1] + 1 == longitude_key and segments[-1][2] == city_index:
                segments[-1][1] = longitude_key
            else:
                segments.append([longitude_key, longitude_key, city_index])
        rows[str(latitude_key)] = segments

    return {
        "schemaVersion": 1,
        "gridPrecision": 1,
        "sourceCoordinateSystem": "GCJ-02",
        "boundaryCoordinateSystem": "WGS-84",
        "source": {
            "project": "JayMuShui/chinese-global-compliant-geodata",
            "path": "src/geojson/countries/as/chn/global/chn-level-2.json",
            "commit": source_commit,
            "sha256": hashlib.sha256(source_bytes).hexdigest(),
            "license": "MIT",
            "upstream": "中国国家地理信息公共服务平台—天地图",
        },
        "cities": indexed_cities,
        "rows": rows,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    arguments = parser.parse_args()
    payload = build_index(arguments.source, arguments.source_commit)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n"
    with tempfile.NamedTemporaryFile(
        "w", encoding="utf-8", newline="\n", prefix=f".{arguments.output.name}.",
        suffix=".tmp", dir=arguments.output.parent, delete=False
    ) as temporary:
        temporary.write(rendered)
        temporary_path = Path(temporary.name)
    os.replace(temporary_path, arguments.output)
    print(
        "PREFECTURE_GRID_OK "
        f"cities={len(payload['cities'])} rows={len(payload['rows'])} "
        f"bytes={len(rendered.encode('utf-8'))} output={arguments.output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
