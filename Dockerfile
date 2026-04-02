FROM eclipse-temurin:17-jdk-alpine

WORKDIR /app

COPY NovaMediaServer.java .
COPY smart-media-player.html .
COPY nova_database.json .

RUN javac NovaMediaServer.java

EXPOSE 10000

CMD ["java", "NovaMediaServer"]