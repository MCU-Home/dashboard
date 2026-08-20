# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""``device/boards``, ``device/new`` and ``device/matter-pairing``.

The three verbs that make the browser able to *start* a device instead of
only editing one that already exists. What is worth asserting here is not
that files appeared — it is the boundary each of them sits on:

* the catalogue a form offers comes from the builder's registry, so a
  wizard cannot offer hardware nobody brought up;
* every refusal is the builder's, arriving with the fix hint the command
  line prints, and a refused creation leaves nothing behind;
* drawing commissioning credentials writes them where secrets live and
  answers with none of them — the passcode has one door (ADR 0007) and
  this is not it.
"""

from __future__ import annotations

from pathlib import Path

from mcuhome.model import registry

from tests.python.conftest import ADMIN_HEADERS, NON_ADMIN_HEADERS, call

BOARD = "nrf7002dk/nrf5340/cpuapp"


def _outline() -> dict:
    """A wizard's picks, taken from the registry rather than written out."""
    driver = next(iter(registry.DRIVERS.values()))
    bus = next(entry for entry in registry.BOARDS[BOARD].buses if entry.kind == driver.bus)
    cluster = next(iter(registry.CLUSTERS.values()))
    channel = next(
        entry.name for entry in driver.channels.values() if entry.quantity == cluster.quantity
    )
    device_type = next(
        entry.name
        for entry in registry.DEVICE_TYPES.values()
        if cluster.name in entry.mandatory_clusters
    )
    return {
        "buses": [{"id": "i2c0", "controller": bus.controller}],
        "peripherals": [{"id": "probe", "driver": driver.compatible, "bus": "i2c0"}],
        "endpoints": [
            {
                "device_type": device_type,
                "clusters": [
                    {"cluster": cluster.name, "source": f"probe.{channel}", "sampling": "30s"}
                ],
            }
        ],
    }


# --------------------------------------------------------------------------
# device/boards
# --------------------------------------------------------------------------


async def test_the_catalogue_is_the_builders_registry(client) -> None:
    async with client.ws_connect("/ws") as ws:
        payload = (await call(ws, "device/boards"))["payload"]

    assert [entry["name"] for entry in payload["boards"]] == list(registry.BOARDS)
    assert [entry["compatible"] for entry in payload["drivers"]] == list(registry.DRIVERS)
    assert [entry["name"] for entry in payload["device_types"]] == list(registry.DEVICE_TYPES)


async def test_a_board_says_which_buses_it_breaks_out(client) -> None:
    """What a peripheral picker needs, and what it must not invent."""
    async with client.ws_connect("/ws") as ws:
        payload = (await call(ws, "device/boards"))["payload"]

    board = next(entry for entry in payload["boards"] if entry["name"] == BOARD)
    assert [bus["controller"] for bus in board["buses"]] == [
        bus.controller for bus in registry.BOARDS[BOARD].buses
    ]


async def test_what_is_planned_travels_with_what_is_supported(client) -> None:
    """ "Not yet, because …" is a better answer than an absence."""
    async with client.ws_connect("/ws") as ws:
        payload = (await call(ws, "device/boards"))["payload"]

    planned = {entry["name"]: entry["reason"] for entry in payload["planned_boards"]}
    assert planned == dict(registry.PLANNED_BOARDS)


async def test_the_catalogue_answers_a_non_admin(roster_ingress_client) -> None:
    """It says what the software supports, nothing about this deployment."""
    async with roster_ingress_client.ws_connect("/ws", headers=NON_ADMIN_HEADERS) as ws:
        frame = await call(ws, "device/boards")

    assert frame["type"] == "result"


# --------------------------------------------------------------------------
# device/new
# --------------------------------------------------------------------------


async def test_a_new_device_is_created_and_comes_back_ready_to_edit(client, tree: Path) -> None:
    """The answer is ``device/get``'s, so the editor opens without asking again."""
    async with client.ws_connect("/ws") as ws:
        payload = (await call(ws, "device/new", {"name": "attic", "board": BOARD}))["payload"]

    entry = tree / "devices" / "attic" / "main.yaml"
    assert entry.is_file()
    assert payload["content"] == entry.read_text("utf-8")
    assert payload["device"]["name"] == "attic"
    assert payload["device"]["content_hash"]


async def test_a_created_device_is_in_the_list_that_follows(client) -> None:
    async with client.ws_connect("/ws") as ws:
        await call(ws, "device/new", {"name": "attic", "board": BOARD})
        listed = (await call(ws, "device/list", frame_id="2"))["payload"]

    assert "attic" in [entry["name"] for entry in listed["devices"]]


async def test_the_outline_a_form_collected_becomes_configuration(client, tree: Path) -> None:
    async with client.ws_connect("/ws") as ws:
        payload = (
            await call(ws, "device/new", {"name": "attic", "board": BOARD, "outline": _outline()})
        )["payload"]

    text = payload["content"]
    assert "hardware:" in text and "# hardware:" not in text
    assert "    probe:" in text

    # And it resolves — which is the assertion worth having. A wizard
    # that writes something the next command rejects has helped nobody.
    # The credentials are the one thing a new device is still missing,
    # deliberately, and drawing them is the step the file itself names.
    async with client.ws_connect("/ws") as ws:
        await call(ws, "device/matter-pairing", {"name": "attic"})
        result = (await call(ws, "device/validate", {"name": "attic"}, frame_id="2"))["payload"]

    assert result["errors"] == []
    assert result["ok"] is True
    assert result["device"]["endpoints"]


async def test_without_an_outline_the_file_carries_the_commented_example(client) -> None:
    async with client.ws_connect("/ws") as ws:
        payload = (await call(ws, "device/new", {"name": "attic", "board": BOARD}))["payload"]

    assert "# hardware:" in payload["content"]
    assert "\nhardware:" not in payload["content"]


async def test_a_friendly_name_is_written_through(client) -> None:
    async with client.ws_connect("/ws") as ws:
        payload = (
            await call(
                ws, "device/new", {"name": "attic", "board": BOARD, "friendly_name": "The Attic"}
            )
        )["payload"]

    assert 'friendly_name: "The Attic"' in payload["content"]


# --------------------------------------------------------------------------
# device/new: the refusals, which are all the builder's
# --------------------------------------------------------------------------


async def test_an_existing_device_is_a_conflict_and_not_an_overwrite(client, tree: Path) -> None:
    before = (tree / "devices" / "bench-node" / "main.yaml").read_text("utf-8")
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "device/new", {"name": "bench-node", "board": BOARD})

    assert frame["error"]["code"] == "conflict"
    assert (tree / "devices" / "bench-node" / "main.yaml").read_text("utf-8") == before


async def test_a_board_nobody_brought_up_is_refused_with_the_ones_that_exist(client) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "device/new", {"name": "attic", "board": "nrf99dk"})

    assert frame["type"] == "error"
    assert BOARD in frame["error"]["errors"][0]["hint"]


async def test_a_name_that_cannot_become_a_hostname_is_refused(client, tree: Path) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "device/new", {"name": "Attic Room", "board": BOARD})

    assert frame["type"] == "error"
    assert not (tree / "devices" / "Attic Room").exists()


async def test_an_outline_naming_nothing_real_is_refused_before_anything_is_written(
    client, tree: Path
) -> None:
    outline = _outline()
    outline["peripherals"][0]["driver"] = "acme,nonesuch"
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "device/new", {"name": "attic", "board": BOARD, "outline": outline})

    assert frame["type"] == "error"
    assert not (tree / "devices" / "attic").exists()


async def test_a_malformed_outline_is_answered_as_a_malformed_frame(client) -> None:
    """Shape is this side's to judge; every name in it is the builder's."""
    async with client.ws_connect("/ws") as ws:
        frame = await call(
            ws, "device/new", {"name": "attic", "board": BOARD, "outline": {"buses": "i2c0"}}
        )

    assert frame["error"]["code"] == "bad_request"
    assert "list of objects" in frame["error"]["message"]


async def test_creating_a_device_needs_an_administrator(roster_ingress_client, tree: Path) -> None:
    async with roster_ingress_client.ws_connect("/ws", headers=NON_ADMIN_HEADERS) as ws:
        frame = await call(ws, "device/new", {"name": "attic", "board": BOARD})

    assert frame["error"]["code"] == "unauthorized"
    assert not (tree / "devices" / "attic").exists()


async def test_an_administrator_may_create_one(roster_ingress_client) -> None:
    async with roster_ingress_client.ws_connect("/ws", headers=ADMIN_HEADERS) as ws:
        frame = await call(ws, "device/new", {"name": "attic", "board": BOARD})

    assert frame["type"] == "result"


# --------------------------------------------------------------------------
# device/matter-pairing
# --------------------------------------------------------------------------


async def test_drawing_credentials_writes_them_where_secrets_live(client, tree: Path) -> None:
    async with client.ws_connect("/ws") as ws:
        await call(ws, "device/new", {"name": "attic", "board": BOARD})
        payload = (await call(ws, "device/matter-pairing", {"name": "attic"}, frame_id="2"))[
            "payload"
        ]

    secrets = Path(payload["secrets_file"])
    assert secrets.is_file()
    assert secrets.read_text("utf-8").count("matter_") >= 3
    assert payload["replaced"] is False
    # The configuration a project commits carries references, not values.
    entry = (tree / "devices" / "attic" / "main.yaml").read_text("utf-8")
    assert "!secret matter_passcode" in entry


async def test_the_codes_do_not_come_back_from_the_command_that_drew_them(client) -> None:
    """One command carries passcodes, and this is not it (ADR 0007)."""
    async with client.ws_connect("/ws") as ws:
        await call(ws, "device/new", {"name": "attic", "board": BOARD})
        payload = (await call(ws, "device/matter-pairing", {"name": "attic"}, frame_id="2"))[
            "payload"
        ]

    flat = repr(payload)
    for forbidden in ("passcode", "qr_payload", "manual_code", "salt", "discriminator"):
        assert forbidden not in flat


async def test_the_codes_are_one_command_away(client) -> None:
    """The round trip the previous test's rule costs, and what it buys."""
    async with client.ws_connect("/ws") as ws:
        await call(ws, "device/new", {"name": "attic", "board": BOARD})
        await call(ws, "device/matter-pairing", {"name": "attic"}, frame_id="2")
        payload = (await call(ws, "device/commissioning", {"name": "attic"}, frame_id="3"))[
            "payload"
        ]

    assert payload["ok"] is True
    assert payload["commissioning"]["qr_payload"]
    assert payload["commissioning"]["test_credentials"] is False


async def test_credentials_that_exist_are_not_replaced_without_being_told_to(client) -> None:
    async with client.ws_connect("/ws") as ws:
        await call(ws, "device/new", {"name": "attic", "board": BOARD})
        await call(ws, "device/matter-pairing", {"name": "attic"}, frame_id="2")
        frame = await call(ws, "device/matter-pairing", {"name": "attic"}, frame_id="3")

    assert frame["error"]["code"] == "conflict"
    assert "already has commissioning credentials" in frame["error"]["message"]


async def test_replacing_is_possible_when_it_is_said_in_so_many_words(client) -> None:
    async with client.ws_connect("/ws") as ws:
        await call(ws, "device/new", {"name": "attic", "board": BOARD})
        first = (await call(ws, "device/matter-pairing", {"name": "attic"}, frame_id="2"))[
            "payload"
        ]
        before = Path(first["secrets_file"]).read_text("utf-8")
        payload = (
            await call(ws, "device/matter-pairing", {"name": "attic", "force": True}, frame_id="3")
        )["payload"]

    assert payload["replaced"] is True
    assert Path(payload["secrets_file"]).read_text("utf-8") != before


async def test_a_device_that_is_not_there_is_not_found(client) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "device/matter-pairing", {"name": "nonesuch"})

    assert frame["error"]["code"] == "not_found"


async def test_force_has_to_be_a_boolean(client) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "device/matter-pairing", {"name": "bench-node", "force": "yes"})

    assert frame["error"]["code"] == "bad_request"


async def test_drawing_credentials_needs_an_administrator(roster_ingress_client) -> None:
    async with roster_ingress_client.ws_connect("/ws", headers=NON_ADMIN_HEADERS) as ws:
        frame = await call(ws, "device/matter-pairing", {"name": "bench-node"})

    assert frame["error"]["code"] == "unauthorized"


async def test_a_rewritten_configuration_hands_out_a_fresh_hash(client) -> None:
    """The write moved main.yaml; an editor holding the old hash has to know.

    Re-scanning inside the command is what turns that into a
    ``device_changed`` event instead of a conflict on somebody's next
    save.
    """
    async with client.ws_connect("/ws") as ws:
        created = (await call(ws, "device/new", {"name": "attic", "board": BOARD}))["payload"]
        await call(ws, "device/matter-pairing", {"name": "attic"}, frame_id="2")
        reopened = (await call(ws, "device/get", {"name": "attic"}, frame_id="3"))["payload"]

    assert reopened["device"]["content_hash"] != created["device"]["content_hash"]
