"""Offline prefecture-level lookup for the dashboard's 0.1 degree GCJ-02 grids."""

from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path
from typing import Any, Mapping


DEFAULT_INDEX_PATH = Path(__file__).resolve().parent / "data" / "china-prefecture-grid.json"


@dataclass(frozen=True)
class Prefecture:
    code: str
    label: str
    province: str
    longitude: float
    latitude: float


class PrefectureGridIndex:
    """Resolve the exact rounded grid keys emitted by LOCATION_GRIDS."""

    def __init__(self, payload: Mapping[str, Any]):
        if payload.get("schemaVersion") != 1 or payload.get("gridPrecision") != 1:
            raise ValueError("unsupported prefecture grid index")

        cities: list[Prefecture] = []
        for raw in payload.get("cities", []):
            cities.append(
                Prefecture(
                    code=str(raw["code"]),
                    label=str(raw["label"]),
                    province=str(raw.get("province") or ""),
                    longitude=float(raw["longitude"]),
                    latitude=float(raw["latitude"]),
                )
            )
        if not cities:
            raise ValueError("prefecture grid index contains no cities")

        rows: dict[int, tuple[tuple[int, int, int], ...]] = {}
        for raw_latitude, raw_segments in payload.get("rows", {}).items():
            latitude = int(raw_latitude)
            segments: list[tuple[int, int, int]] = []
            previous_end: int | None = None
            for raw_segment in raw_segments:
                start, end, city_index = (int(value) for value in raw_segment)
                if start > end or city_index < 0 or city_index >= len(cities):
                    raise ValueError("invalid prefecture grid segment")
                if previous_end is not None and start <= previous_end:
                    raise ValueError("overlapping prefecture grid segments")
                segments.append((start, end, city_index))
                previous_end = end
            rows[latitude] = tuple(segments)

        self.cities = tuple(cities)
        self.rows = rows
        self.source = dict(payload.get("source") or {})

    @classmethod
    def load(cls, path: Path = DEFAULT_INDEX_PATH) -> "PrefectureGridIndex":
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ValueError(f"cannot load prefecture grid index: {path}") from error
        return cls(payload)

    def lookup_gcj02(self, longitude: float, latitude: float) -> Prefecture | None:
        longitude_key = round(float(longitude) * 10)
        latitude_key = round(float(latitude) * 10)
        for start, end, city_index in self.rows.get(latitude_key, ()):
            if longitude_key < start:
                break
            if longitude_key <= end:
                return self.cities[city_index]
        return None
