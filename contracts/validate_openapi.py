from __future__ import annotations

import re
import sys
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any

import yaml


CONTRACT = Path(__file__).with_name("openapi.yaml")
EXPECTED_OPERATIONS = {
    ("/recommendations", "post"): ("createRecommendation", "201"),
    ("/recommendations/{id}/reroll", "post"): ("rerollRecommendation", "200"),
    ("/recommendations/{id}/feedback", "post"): ("submitRecommendationFeedback", "201"),
    ("/recommendations/{id}/behaviors", "post"): ("submitRecommendationBehavior", "201"),
    ("/recommendations/{id}/deep-evidence", "post"): ("deepenRecommendationEvidence", "200"),
}


def fail(message: str) -> None:
    raise AssertionError(message)


def load_contract() -> dict[str, Any]:
    with CONTRACT.open(encoding="utf-8") as source:
        document = yaml.safe_load(source)
    if not isinstance(document, dict):
        fail("contract root must be an object")
    return document


def resolve(document: dict[str, Any], reference: str) -> Any:
    if not reference.startswith("#/"):
        fail(f"only local references are allowed: {reference}")
    current: Any = document
    for raw_part in reference[2:].split("/"):
        part = raw_part.replace("~1", "/").replace("~0", "~")
        if not isinstance(current, dict) or part not in current:
            fail(f"unresolved reference: {reference}")
        current = current[part]
    return current


def inspect_references(document: dict[str, Any], node: Any) -> None:
    if isinstance(node, dict):
        reference = node.get("$ref")
        if reference is not None:
            resolve(document, reference)
        for value in node.values():
            inspect_references(document, value)
    elif isinstance(node, list):
        for value in node:
            inspect_references(document, value)


def dereference(document: dict[str, Any], node: dict[str, Any]) -> dict[str, Any]:
    while "$ref" in node:
        resolved = resolve(document, node["$ref"])
        if not isinstance(resolved, dict):
            fail(f"reference does not resolve to an object: {node['$ref']}")
        node = resolved
    return node


def validate_value(
    document: dict[str, Any], schema: dict[str, Any], value: Any, location: str
) -> None:
    schema = dereference(document, schema)
    if value is None:
        if schema.get("nullable") is True:
            return
        fail(f"{location}: null is not allowed")

    schema_type = schema.get("type")
    if schema_type == "object":
        if not isinstance(value, dict):
            fail(f"{location}: expected object")
        properties = schema.get("properties", {})
        for required in schema.get("required", []):
            if required not in value:
                fail(f"{location}: missing required property {required}")
        if schema.get("additionalProperties") is False:
            unknown = set(value) - set(properties)
            if unknown:
                fail(f"{location}: unknown properties {sorted(unknown)}")
        for name, child in value.items():
            if name in properties:
                validate_value(document, properties[name], child, f"{location}.{name}")
    elif schema_type == "array":
        if not isinstance(value, list):
            fail(f"{location}: expected array")
        if len(value) < schema.get("minItems", 0):
            fail(f"{location}: too few items")
        if "maxItems" in schema and len(value) > schema["maxItems"]:
            fail(f"{location}: too many items")
        if schema.get("uniqueItems") and len({repr(item) for item in value}) != len(value):
            fail(f"{location}: items must be unique")
        for index, item in enumerate(value):
            validate_value(document, schema.get("items", {}), item, f"{location}[{index}]")
    elif schema_type == "string":
        if not isinstance(value, str):
            fail(f"{location}: expected string")
        if len(value) < schema.get("minLength", 0):
            fail(f"{location}: string is too short")
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            fail(f"{location}: string is too long")
        if "pattern" in schema and re.fullmatch(schema["pattern"], value) is None:
            fail(f"{location}: value does not match pattern")
        if schema.get("format") == "uuid":
            uuid.UUID(value)
        elif schema.get("format") == "date-time":
            datetime.fromisoformat(value.replace("Z", "+00:00"))
    elif schema_type == "integer":
        if not isinstance(value, int) or isinstance(value, bool):
            fail(f"{location}: expected integer")
    elif schema_type == "number":
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            fail(f"{location}: expected number")
    elif schema_type == "boolean" and not isinstance(value, bool):
        fail(f"{location}: expected boolean")

    if "enum" in schema and value not in schema["enum"]:
        fail(f"{location}: {value!r} is outside enum")
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if "minimum" in schema and value < schema["minimum"]:
            fail(f"{location}: value is below minimum")
        if "maximum" in schema and value > schema["maximum"]:
            fail(f"{location}: value is above maximum")


def inspect_operations(document: dict[str, Any]) -> None:
    paths = document.get("paths", {})
    actual = {
        (path, method)
        for path, path_item in paths.items()
        for method in path_item
        if method in {"get", "post", "put", "patch", "delete"}
    }
    if actual != set(EXPECTED_OPERATIONS):
        fail(f"operation set differs from V0.4 contract: {sorted(actual)}")

    operation_ids: set[str] = set()
    for (path, method), (operation_id, success_code) in EXPECTED_OPERATIONS.items():
        operation = paths[path][method]
        if operation.get("operationId") != operation_id:
            fail(f"{method.upper()} {path}: unexpected operationId")
        if operation_id in operation_ids:
            fail(f"duplicate operationId: {operation_id}")
        operation_ids.add(operation_id)
        if success_code not in operation.get("responses", {}):
            fail(f"{method.upper()} {path}: missing success response {success_code}")

        parameters = [
            dereference(document, parameter)
            for parameter in operation.get("parameters", [])
        ]
        anonymous_headers = [
            parameter
            for parameter in parameters
            if parameter.get("in") == "header"
            and parameter.get("name") == "X-Anonymous-User-Id"
        ]
        if len(anonymous_headers) != 1 or anonymous_headers[0].get("required") is not True:
            fail(f"{method.upper()} {path}: anonymous user header must be required")

        for placeholder in re.findall(r"{([^}]+)}", path):
            matches = [
                parameter
                for parameter in parameters
                if parameter.get("in") == "path" and parameter.get("name") == placeholder
            ]
            if len(matches) != 1 or matches[0].get("required") is not True:
                fail(f"{method.upper()} {path}: path parameter {placeholder} is invalid")


def inspect_examples_and_defaults(document: dict[str, Any], node: Any, location: str) -> None:
    if isinstance(node, dict):
        if "schema" in node and "example" in node:
            validate_value(document, node["schema"], node["example"], f"{location}.example")
        if "type" in node or "$ref" in node:
            schema = dereference(document, node)
            if "example" in schema:
                validate_value(document, schema, schema["example"], f"{location}.example")
            if "default" in schema:
                validate_value(document, schema, schema["default"], f"{location}.default")
        for key, value in node.items():
            inspect_examples_and_defaults(document, value, f"{location}.{key}")
    elif isinstance(node, list):
        for index, value in enumerate(node):
            inspect_examples_and_defaults(document, value, f"{location}[{index}]")


def inspect_feedback_shape(document: dict[str, Any]) -> None:
    feedback = document["components"]["schemas"]["SubmitFeedbackRequest"]
    if set(feedback.get("properties", {})) != {"result", "flavorTags"}:
        fail("SubmitFeedbackRequest must contain result and optional flavorTags in V0.4")
    if feedback.get("required") != ["result"]:
        fail("SubmitFeedbackRequest.result must be required")


def main() -> int:
    document = load_contract()
    if document.get("openapi") != "3.0.3":
        fail("OpenAPI version must be 3.0.3")
    inspect_references(document, document)
    inspect_operations(document)
    inspect_feedback_shape(document)
    inspect_examples_and_defaults(document, document, "contract")
    print(
        "CONTRACT_OK",
        f"openapi={document['openapi']}",
        f"operations={len(EXPECTED_OPERATIONS)}",
        f"schemas={len(document['components']['schemas'])}",
    )
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (AssertionError, ValueError, TypeError, yaml.YAMLError) as error:
        print(f"CONTRACT_INVALID: {error}", file=sys.stderr)
        sys.exit(1)
