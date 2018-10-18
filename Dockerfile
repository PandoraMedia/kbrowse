FROM openjdk:8-jre-alpine

EXPOSE 4000

ENV CONFIG_FILE default.yml

COPY docker-files/kbrowse-master/target/kbrowse-*-SNAPSHOT-standalone.jar /app/
COPY config/* /app/
COPY launch.sh /app/

WORKDIR "/app"
CMD ["./launch.sh"]
