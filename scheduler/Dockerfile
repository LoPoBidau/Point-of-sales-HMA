FROM eclipse-temurin:17-jre
WORKDIR /app
COPY build/install/stock-scheduler/ /app/
RUN chmod +x /app/bin/stock-scheduler
CMD ["./bin/stock-scheduler"]
