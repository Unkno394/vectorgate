FROM debian:bookworm-slim AS build
RUN apt-get update && apt-get install -y --no-install-recommends build-essential cmake && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY CMakeLists.txt .
COPY src ./src
RUN cmake -S . -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build --parallel

FROM debian:bookworm-slim
COPY --from=build /src/build/reverse_proxy /usr/local/bin/reverse_proxy
EXPOSE 8080
ENTRYPOINT ["reverse_proxy", "8080", "backend", "3000"]
