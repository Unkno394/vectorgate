FROM python:3.12-slim
WORKDIR /app
COPY trusted_normal_backend.py ./
EXPOSE 3000
ENTRYPOINT ["python3", "trusted_normal_backend.py", "--port", "3000"]
