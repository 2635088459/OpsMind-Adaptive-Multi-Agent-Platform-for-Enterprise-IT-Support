"""`python -m event_relay` — the container entrypoint."""

from __future__ import annotations

import logging
import sys

from event_relay.consumer import RelayConsumer
from event_relay.observability import configure_observability
from event_relay.settings import get_settings


def main() -> int:
    settings = get_settings()
    logging.basicConfig(
        level=getattr(logging, settings.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        stream=sys.stdout,
    )
    logging.getLogger("pika").setLevel(logging.WARNING)
    configure_observability(settings)
    logging.getLogger("event_relay").info(
        "action=relay_starting agent_runtime=%s tool_gateway=%s memory_knowledge=%s otel=%s",
        settings.agent_runtime_base_url, settings.tool_gateway_base_url,
        settings.memory_knowledge_base_url, settings.otel_exporter,
    )
    RelayConsumer(settings).run_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
