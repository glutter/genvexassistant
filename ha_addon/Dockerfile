FROM eclipse-temurin:17-jre

WORKDIR /app

# Install jq for parsing options.json
RUN apt-get update && apt-get install -y jq && rm -rf /var/lib/apt/lists/*

# Copy the built jar file
COPY app.jar /app/app.jar
COPY run.sh /app/run.sh

RUN chmod +x /app/run.sh

CMD [ "/app/run.sh" ]
