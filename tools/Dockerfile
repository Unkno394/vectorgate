FROM python:3.12-slim
WORKDIR /app
COPY trusted_normal_backend.py ./
COPY generate_trusted_normal_traffic.py ./
EXPOSE 3000
ENTRYPOINT ["python3", "trusted_normal_backend.py", "--port", "3000"]
