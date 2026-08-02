# Multi-stage build: compile the dependency set in a full python:3.12-slim
# image, then copy only the installed packages + app code into a lean
# runtime image. Keeps uv and pip's build tooling out of the final image.
FROM python:3.12-slim AS build
WORKDIR /app
RUN pip install --no-cache-dir uv
COPY pyproject.toml .
COPY src/ src/
# Non-editable install: the wheel is built from src/inference_service (see
# [tool.hatch.build.targets.wheel] in pyproject.toml) and lands in
# site-packages as the top-level `inference_service` package — there is no
# `src.` prefix at import time, unlike running straight out of a source
# checkout with `-e .`.
RUN uv pip install --system --no-cache .

FROM python:3.12-slim
RUN groupadd -r lynceus && useradd -r -g lynceus lynceus
WORKDIR /app
COPY --from=build /usr/local/lib/python3.12/site-packages /usr/local/lib/python3.12/site-packages
COPY --from=build /usr/local/bin/uvicorn /usr/local/bin/uvicorn
COPY models/ models/
USER lynceus
EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD python -c "import httpx; httpx.get('http://localhost:8082/api/v1/scoring/health').raise_for_status()" || exit 1
ENTRYPOINT ["uvicorn", "inference_service.main:app", "--host", "0.0.0.0", "--port", "8082"]
