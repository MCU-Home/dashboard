# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""ADR 0011 decision 2: a declared range, and a refusal that names both."""

from __future__ import annotations

import importlib.metadata

import pytest

from mcuhome.ui import versions
from mcuhome.ui.builder import MCUHOME_VERSION
from mcuhome.ui.versions import IncompatibleBuilderError, check_mcuhome_version


def test_the_installed_builder_is_inside_the_supported_range() -> None:
    # If this ever fails, one of the two repositories moved and the
    # other has not been told — which is exactly what the range is for.
    check_mcuhome_version(MCUHOME_VERSION)


@pytest.mark.parametrize("version", ["0.1.0", "0.1.0.dev0", "0.1.7", "0.1.99"])
def test_versions_inside_the_range_are_accepted(version: str) -> None:
    check_mcuhome_version(version)


@pytest.mark.parametrize("version", ["0.0.9", "0.2.0", "0.2.0.dev1", "1.0.0"])
def test_versions_outside_it_are_refused(version: str) -> None:
    with pytest.raises(IncompatibleBuilderError) as caught:
        check_mcuhome_version(version)

    message = str(caught.value)
    assert version in message
    assert versions.MCUHOME_VERSION_SPEC in message
    assert versions.DASHBOARD_VERSION in message


def test_the_range_is_stated_the_way_a_dependency_would_be() -> None:
    assert versions.MCUHOME_VERSION_SPEC == "mcuhome-workbench>=0.1.0,<0.2.0"


def test_the_range_names_the_distribution_that_is_actually_imported() -> None:
    """Firmware ADR 0020 split the builder; the plain name is the CLI's.

    A range spelled ``mcuhome>=…`` would, once these are published,
    declare a dependency on the command-line distribution — a console
    script this package neither imports nor wants — while saying nothing
    about the one it does import.
    """
    assert versions.MCUHOME_PACKAGE == "mcuhome-workbench"
    assert importlib.metadata.version(versions.MCUHOME_PACKAGE) == MCUHOME_VERSION
    assert versions.release_tuple(MCUHOME_VERSION) >= versions.release_tuple(
        versions.MCUHOME_VERSION_MIN
    )


def test_the_model_version_is_two_and_says_so_at_both_ends() -> None:
    assert versions.MODEL_VERSION == 2
    assert versions.MODEL_VERSION_MIN <= versions.MODEL_VERSION <= versions.MODEL_VERSION_MAX


def test_release_tuples_ignore_development_suffixes() -> None:
    assert versions.release_tuple("0.1.0.dev0") == (0, 1, 0)
    assert versions.release_tuple("v1.2.3") == (1, 2, 3)
    assert versions.release_tuple("nonsense") == ()
