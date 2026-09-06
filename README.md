# mcuhome-ui

mcuhome-ui is MCUHome's web interface: a browser front end for the devices of
one project — editing a device's YAML, validating it, building, signing and
flashing its firmware, and managing the project's secrets, shared
configurations and options. It is the graphical half of the project's tooling,
working on the same project tree the command line works on.

## State of this repository

The interface is being rebuilt, and the earlier prototype has been removed
rather than carried along. What the repository holds today is the design the
rebuild follows — a rendered screen for every approved board plus the design
notes behind them, under [`docs/design/`](docs/design/) — and the checks that
run over the tree.

What is being built on top of it: a Kotlin / Compose Multiplatform front end
for the web platform and a Python back end on top of
[mcuhome-workbench](https://github.com/mcu-home/mcuhome-workbench), talking to
each other only through an API over HTTP and WebSocket.

## Layout

| Path | Purpose |
|---|---|
| `docs/design/` | The design reference: rendered screens and design notes |
| `scripts/` | The development gates — the `test` and `lint` dispatchers and their wrappers |
| `.github/` | Workflows, dependency updates, code ownership |

## Development — how to work on this repository

This repository has its own virtual environment in `.venv/`; nothing is
installed into the system Python or into another repository's environment.
`scripts/` holds the development tooling: `scripts/test` and `scripts/lint`
dispatch the checks — `all` runs every one, `list` names them, `<name>` runs
one — and each check is its own wrapper in `scripts/test.d/` or
`scripts/lint.d/`. The wrappers select `.venv` themselves (never activate one
by hand) and are exactly what CI runs, one job per check.

Needs Python ≥3.13. The `.venv` holds the linters and nothing else; they are
the `dev` dependency group of the root `pyproject.toml`, which declares no
package of its own.

```sh
python3 -m venv .venv && .venv/bin/pip install --group dev
```

```sh
scripts/lint all
scripts/lint list
```

`scripts/test` has no wrappers while the repository holds no code to test; it
says so and passes.

The rules that hold across every MCUHome repository — coding standards,
commits, licensing — are in the organization's
[contributing guide](https://github.com/mcu-home/.github/blob/main/CONTRIBUTING.md).

## Contributing and support

Bugs, questions and feature requests belong in this repository's
[issue tracker](https://github.com/mcu-home/mcuhome-ui/issues). The
organization's
[contributing rules](https://github.com/mcu-home/.github/blob/main/CONTRIBUTING.md)
apply before a pull request, and vulnerabilities go through the
[security policy](https://github.com/mcu-home/.github/blob/main/SECURITY.md).

## License

Apache License 2.0, see [`LICENSE`](LICENSE).
