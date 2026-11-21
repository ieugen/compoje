# Reference ansible-vault (ansible-core) for cross-implementation tests.
# Used by test/integration/ansible_vault_helpers.clj via testcontainers.
FROM python:3.12-slim

# ansible requires a UTF-8 locale
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

RUN pip install ansible-core==2.21.6

WORKDIR /work
CMD ["sleep", "infinity"]
