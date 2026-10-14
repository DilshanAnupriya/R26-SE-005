# M2 sprint risk service on its own. Build from the ai-service folder:
#   az acr build --registry <name> --image m2-service:1.0 --file Dockerfile.m2 .
FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /srv

# Separate layer so a code-only change reuses the cached install.
COPY requirements-m2.txt .
RUN pip install --no-cache-dir -r requirements-m2.txt

# Only M2's code and models: the image carries nothing the service does not serve.
COPY app/__init__.py app/m2_main.py app/risk_predictor.py app/risk_features.py \
     app/collaboration_predictor.py app/collaboration_features.py ./app/
COPY models/overbudget_model_bundle.pkl models/reqchange_model_bundle.pkl ./models/

RUN useradd --system --no-create-home m2 && chown -R m2 /srv
USER m2

EXPOSE 8000
CMD ["uvicorn", "app.m2_main:app", "--host", "0.0.0.0", "--port", "8000"]
