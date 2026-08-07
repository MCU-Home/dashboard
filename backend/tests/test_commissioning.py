# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""``device/commissioning``: the codes, and only when asked for.

Two properties matter here and they pull in opposite directions.

The commissioning view is **supposed** to show the passcode — a QR code
that hides the setup payload commissions nothing — so this command has to
return it in full, byte-identical to what ``mcuhome validate`` prints, or
the dashboard and the CLI would disagree about what a device's codes are.

And exactly because it returns it, no other command may. The negative
assertions below are the ones worth keeping: ``device/list``,
``device/get`` and ``device/validate`` hand their answers to every open
browser tab without anybody asking, and the pairing tuple does not belong
in any of them (ADR 0007; ``builder.device_summary``).
"""

from __future__ import annotations

import json
from pathlib import Path

from mcuhome.pairing import TEST_DISCRIMINATOR, TEST_PAIRING

from mcuhome_dashboard.app import AppState, create_app
from mcuhome_dashboard.config import Config
from mcuhome_dashboard.security import TrustMode
from tests.conftest import VALID_CONFIG, call, write_device

#: The same device with Matter switched off — nothing to commission.
NO_MATTER_CONFIG = """\
device:
  name: plain-node
  board: nrf7002dk/nrf5340/cpuapp

network:
  thread:
    device_role: ftd
  matter:
    enabled: false
"""


async def test_the_codes_are_the_ones_the_cli_prints(client) -> None:
    async with client.ws_connect("/ws") as ws:
        payload = (await call(ws, "device/commissioning", {"name": "bench-node"}))["payload"]

    assert payload["ok"] is True
    assert payload["errors"] == []
    codes = payload["commissioning"]
    assert codes["qr_payload"] == TEST_PAIRING.qr_payload
    assert codes["manual_code"] == TEST_PAIRING.manual_code
    assert codes["discriminator"] == TEST_DISCRIMINATOR
    # The fixture asks for `use_test_pairing`, and the UI has to be able
    # to say so — published credentials are bench use only.
    assert codes["test_credentials"] is True


async def test_the_qr_payload_is_a_matter_onboarding_payload(client) -> None:
    async with client.ws_connect("/ws") as ws:
        payload = (await call(ws, "device/commissioning", {"name": "bench-node"}))["payload"]

    # What a controller's camera expects to find, and what `wa-qr-code`
    # is handed unmodified.
    assert payload["commissioning"]["qr_payload"].startswith("MT:")


async def test_the_verifier_inputs_never_travel(client) -> None:
    # The salt and the iteration count are inputs to the SPAKE2+
    # verifier. Nothing a human does with a controller needs them, so
    # nothing sends them.
    async with client.ws_connect("/ws") as ws:
        payload = (await call(ws, "device/commissioning", {"name": "bench-node"}))["payload"]

    assert set(payload["commissioning"]) == {
        "qr_payload",
        "manual_code",
        "discriminator",
        "test_credentials",
    }


async def test_no_other_command_carries_the_pairing_tuple(client) -> None:
    async with client.ws_connect("/ws") as ws:
        listed = (await call(ws, "device/list"))["payload"]
        fetched = (await call(ws, "device/get", {"name": "bench-node"}, frame_id="2"))["payload"]
        validated = (await call(ws, "device/validate", {"name": "bench-node"}, frame_id="3"))[
            "payload"
        ]

    secret = str(TEST_PAIRING.passcode)
    for payload in (listed, validated):
        text = json.dumps(payload)
        assert secret not in text
        assert TEST_PAIRING.qr_payload not in text
        assert TEST_PAIRING.manual_code not in text
    # `device/get` returns the file as it is on disk, which is the
    # editor's whole job; what it must not do is *derive* the codes.
    assert TEST_PAIRING.qr_payload not in json.dumps(fetched["summary"])
    assert TEST_PAIRING.qr_payload not in json.dumps(fetched["device"])


async def test_a_device_without_matter_has_nothing_to_commission(client, tree: Path) -> None:
    write_device(tree, "plain-node", NO_MATTER_CONFIG)
    async with client.ws_connect("/ws") as ws:
        payload = (await call(ws, "device/commissioning", {"name": "plain-node"}))["payload"]

    assert payload["ok"] is True
    assert payload["commissioning"] is None


async def test_a_broken_configuration_answers_with_diagnostics_not_an_error(client) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "device/commissioning", {"name": "broken-node"})

    assert frame["type"] == "result"
    payload = frame["payload"]
    assert payload["ok"] is False
    assert payload["commissioning"] is None
    assert payload["errors"][0]["key"] == "device.board"


async def test_commissioning_refuses_an_unknown_device(client) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "device/commissioning", {"name": "nope"})

    assert frame["error"]["code"] == "not_found"


async def test_without_a_tree_commissioning_says_so(aiohttp_client, tmp_path: Path) -> None:
    state = AppState(Config(config_root=tmp_path / "nothing-here", poll_interval=0.0))
    await state.start()
    try:
        client = await aiohttp_client(create_app(state, TrustMode.PUBLIC))
        async with client.ws_connect("/ws") as ws:
            frame = await call(ws, "device/commissioning", {"name": "anything"})
    finally:
        await state.stop()

    assert frame["error"]["code"] == "unavailable"


async def test_the_codes_follow_an_edit(client) -> None:
    # A device's codes are a pure function of its configuration, so the
    # commissioning view must never show a cached set from before a save.
    credentials = VALID_CONFIG.replace(
        "    use_test_pairing: true",
        "    discriminator: 2314\n"
        "    passcode: 84920174\n"
        '    salt: "GBG/P9QwOwhgSLc1fnDR7FWutk+sSsOutub53NjXsp8="',
    )
    async with client.ws_connect("/ws") as ws:
        await call(ws, "device/save", {"name": "bench-node", "content": credentials})
        payload = (await call(ws, "device/commissioning", {"name": "bench-node"}, frame_id="2"))[
            "payload"
        ]

    assert payload["ok"] is True
    assert payload["commissioning"]["discriminator"] == 2314
    assert payload["commissioning"]["test_credentials"] is False
    assert payload["commissioning"]["qr_payload"] != TEST_PAIRING.qr_payload
