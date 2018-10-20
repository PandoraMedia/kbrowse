FROM openjdk:8-jdk-alpine as BUILD

ENV LEIN_ROOT true
RUN apk add --no-cache bash

RUN mkdir -p /home/kbrowse
COPY project.clj lein /home/kbrowse/
COPY src/ /home/kbrowse/src
COPY config/ /home/kbrowse/config
COPY resources/ /home/kbrowse/resources
WORKDIR /home/kbrowse/
RUN ./lein uberjar

FROM openjdk:8-jre-alpine

ENV CONFIG_FILE default.yml
EXPOSE 4000

COPY --from=BUILD /home/kbrowse/target/*.jar /app/
COPY config/* /app/
COPY launch.sh /app/

WORKDIR "/app"
CMD ["./launch.sh"]
