# OpenTDF NiFi
Integration of the [OpenTDF Platform](https://github.com/opentdf/platform) into [NiFi](https://nifi.apache.org/)

Components:
* "Zero Trust Data Format" (ZTDF) Processors: 
  * [ConvertToZTDF](./nifi-tdf-processors/src/main/java/io/opentdf/nifi/ConvertToZTDF.java): A NiFi processor that converts FlowFile content to TDF format. Does not currently support assertions 
  * [ConvertFromZTDF](./nifi-tdf-processors/src/main/java/io/opentdf/nifi/ConvertFromZTDF.java): A NiFi processor that converts TDF formatted FlowFile content to it's plaintext representation
* Controller Services:
  * [OpenTDFControllerService](./nifi-tdf-controller-services-api/src/main/java/io/opentdf/nifi/OpenTDFControllerService.java): A NiFi controller service providing OpenTDF Platform Configuration


#### FlowChart: Generic Plaintext to ZTDF Nifi Flow

```mermaid
---
title: Generic Plaintext to TDF NiFi Flow
---
flowchart TD
   a[FlowFile: \nPlaintext content]
   b["`**UpdateAttribute**`" Add data policy attributes to FlowFile]
   c["`**ConvertToZTDF**`"]
   d["Process ZTDF"]
   e["Handle Error"]
   a -- success --> b
   b -- success --> c
   c -- success (content = ZTDF) --> d
   c -- failure --> e
```

#### FlowChart: Generic ZTDF to Plaintext Nifi Flow
```mermaid
---
title: Generic ZTDF to Plaintext Nifi Flow
---
flowchart TD
   a[FlowFile: \nZTDF content]
   b["`**ConvertFromZTDF**`"]
   c["Process ZTDF"]
   d["Handle Error"]
   a -- success --> b
   b -- success (content = plaintext) --> c
   b -- failure --> d
```

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
