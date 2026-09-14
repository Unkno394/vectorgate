FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY src ./src
RUN javac -d /out src/main/java/main.java

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /out /app/out
EXPOSE 9091/udp 9091
ENTRYPOINT ["java", "-cp", "/app/out", "main", "9090", "60"]
