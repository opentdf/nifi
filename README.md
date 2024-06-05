# OpenTDF NiFi
Integration of the [OpenTDF Platform](https://github.com/opentdf/platform) into [NiFi](https://nifi.apache.org/)

Components:
* ConvertToTDF: A NiFi processor that converts FlowFile content to TDF format 
* ConvertFromTDF: A NiFi processor that converts TDF formatted FlowFile content to it's plaintext representation
* OpenTDFControllerService: A NiFi controller service providing OpenTDF Platform Configuration


# Quick Start - Docker Compose

1. Build the NiFi Archives (NARs) and place in the docker compose mounted volumes
    ```shell
    make compose-package
    ```
1. Start docker compose
    ```shell
    docker compose up
    ```
1. [Log into NiFi](http://localhost:18080/nifi)
