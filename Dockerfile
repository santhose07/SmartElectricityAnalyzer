FROM eclipse-temurin:17-jdk
RUN apt-get update && \
    apt-get install -y --no-install-recommends python3 && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY electricity.db /app/electricity.db
COPY frontend/backend/ ./

RUN javac -cp ".:lib/sqlite-jdbc-3.53.4.0.jar" SmartElectricityAnalyzer.java

EXPOSE 8080

CMD ["java", "-cp", ".:lib/sqlite-jdbc-3.53.4.0.jar", "SmartElectricityAnalyzer"]