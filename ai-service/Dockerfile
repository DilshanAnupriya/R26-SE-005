# Serves every model behind one FastAPI process. Written for a Hugging Face Docker
# Space (port 7860, uid 1000) but nothing in it is specific to Hugging Face: any host
# that injects PORT — Render, Koyeb, Cloud Run — runs the same image unchanged.

# numpy 2.3 and scipy 1.16 need Python 3.11 or newer.
FROM python:3.12-slim

# LightGBM links against OpenMP when it is imported, and the slim image leaves it out:
# without this the service dies at startup with "libgomp.so.1: cannot open shared
# object file" before a single model has loaded.
RUN apt-get update \
 && apt-get install -y --no-install-recommends libgomp1 \
 && rm -rf /var/lib/apt/lists/*

# Spaces run the container as uid 1000 whatever the image says, so the image runs as
# that user everywhere: a permission problem then shows up locally first.
RUN useradd -m -u 1000 user
WORKDIR /home/user/app

# ---- dependencies ---------------------------------------------------------
# Installed before the code is copied, so a change to app/ reuses this layer instead
# of downloading xgboost, lightgbm, catboost and shap again.
COPY requirements.txt .
RUN pip install --no-cache-dir --root-user-action=ignore -r requirements.txt

# ---- runtime --------------------------------------------------------------
# shap imports numba, and numba writes its JIT cache next to the installed package
# unless told otherwise. site-packages belongs to root, so point the cache, and
# matplotlib's config directory, somewhere this user can write.
ENV HOME=/home/user \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    NUMBA_CACHE_DIR=/tmp/numba_cache \
    MPLCONFIGDIR=/tmp/matplotlib

# The service reads only app/ and models/. Training scripts, datasets and tests stay
# out of the image.
COPY --chown=user app ./app
COPY --chown=user models ./models

USER user
EXPOSE 7860

# Loading every bundle takes a while on a shared CPU, hence the long start period.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD python -c "import os, urllib.request; urllib.request.urlopen('http://localhost:%s/health' % os.environ.get('PORT', '7860'), timeout=5)" || exit 1

# exec, so uvicorn receives the stop signal itself and shuts down cleanly.
CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-7860}"]
