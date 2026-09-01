# owner: platform-observability
# spec: SPEC-OP-033
# rollback: git revert <sha>; docker compose -f infrastructure/docker-compose/observability-stack.yml up -d --force-recreate synthetic-probe
#
# Minimal image: pure-stdlib probe.py needs nothing beyond the Python runtime.
FROM python:3.12-slim

WORKDIR /app
COPY probe.py /app/probe.py

USER 65534:65534
EXPOSE 9464

CMD ["python", "/app/probe.py"]
