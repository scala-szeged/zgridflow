FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/scala-*/zgridflow-api.jar /app/api.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-Xms64m", "-Xmx256m", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn", "-jar", "/app/api.jar"]
