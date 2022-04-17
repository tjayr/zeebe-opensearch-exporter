# Zeebe Opensearch Exporter

This is an experimental Zeebe exporter for AWS Opensearch

Camunda 8 doesn't support Opensearch. This is a basic/naive attempt to adapt the Elasticsearch exporter in the Zeebe platform to work with Opensearch.

The code, configuration and tests are ported from the Elasticsearch exporter in release tag [8.0.0](https://github.com/camunda/zeebe/tree/8.0.0) of [Camunda Zeebe](https://github.com/camunda/zeebe).  

The behaviour and configuration is identical to that exporter, just modified to run with an Opensearch client instead of the Elasticsearch client.

## Build and Run

`mvn clean package`

This will assemble a fat jar in `./target/zeebe-opensearch-exporter-0.0.1-jar-with-dependencies.jar` 

After build use `docker-compose.yml` in the root will launch an environment using the packaged exporter jar with a Zeebe broker, Opensearch node and Dashboards.

`docker-compose up`

Indexes are created on demand, so deploy a bpmn process to Zeebe to trigger the creation. 

## Run unit and integration tests

`mvn test verify`

 


