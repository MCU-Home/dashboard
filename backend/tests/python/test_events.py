# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""The event bus, including the case it exists for: a slow consumer."""

from __future__ import annotations

from mcuhome.ui import events
from mcuhome.ui.events import QUEUE_LIMIT, Event, EventBus


def test_an_event_reaches_only_its_topic() -> None:
    bus = EventBus()
    devices = bus.subscribe(events.TOPIC_DEVICES)
    elsewhere = bus.subscribe("something-else")

    bus.publish(events.device_removed("bench-node"))

    assert devices.poll() == Event(events.TOPIC_DEVICES, "device_removed", {"name": "bench-node"})
    assert elsewhere.poll() is None


def test_topics_can_be_added_and_dropped_on_a_live_subscription() -> None:
    bus = EventBus()
    subscription = bus.subscribe()

    bus.publish(events.device_removed("a"))
    assert subscription.poll() is None

    subscription.add(events.TOPIC_DEVICES)
    bus.publish(events.device_removed("b"))
    assert subscription.poll() is not None

    subscription.remove(events.TOPIC_DEVICES)
    bus.publish(events.device_removed("c"))
    assert subscription.poll() is None


def test_closing_a_subscription_takes_it_off_the_bus() -> None:
    bus = EventBus()
    with bus.subscribe(events.TOPIC_DEVICES):
        assert bus.subscriber_count == 1
    assert bus.subscriber_count == 0

    # Publishing to nobody is not an error.
    bus.publish(events.device_removed("a"))


def test_a_consumer_that_stops_reading_loses_the_oldest_events_not_the_newest() -> None:
    bus = EventBus()
    subscription = bus.subscribe(events.TOPIC_DEVICES)

    for index in range(QUEUE_LIMIT + 10):
        bus.publish(events.device_removed(str(index)))

    assert subscription.dropped == 10
    # What survived is the *end* of the stream: a client that catches up
    # wants the current state, not the state it missed ten minutes ago.
    first = subscription.poll()
    assert first is not None
    assert first.payload == {"name": "10"}


def test_publishing_never_raises_for_one_bad_subscriber() -> None:
    bus = EventBus()
    closed = bus.subscribe(events.TOPIC_DEVICES)
    closed.close()
    bus.publish(events.device_removed("a"))
    assert closed.poll() is None
