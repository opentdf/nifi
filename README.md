# OpenTDF NiFi
Integration of the [OpenTDF Platform](https://github.com/opentdf/platform) into [NiFi](https://nifi.apache.org/)

Components:
* "Zero Trust Data Format" (ZTDF) Processors: 
  * [ConvertToZTDF](./nifi-tdf-processors/src/main/java/io/opentdf/nifi/ConvertToZTDF.java): A NiFi processor that converts FlowFile content to ZTDF format. Does not currently support assertions 
  * [ConvertFromZTDF](./nifi-tdf-processors/src/main/java/io/opentdf/nifi/ConvertFromZTDF.java): A NiFi processor that converts ZTDF formatted FlowFile content to it's plaintext representation
* NanoTDF Processors ([See NanoTDF Specification](https://github.com/opentdf/spec/tree/main/schema/nanotdf#readme)):
    * [ConvertToNanoTDF](./nifi-tdf-processors/src/main/java/io/opentdf/nifi/ConvertToNanoTDF.java): A NiFi processor that converts FlowFile content to NanoTDF format. Does not currently support assertions
    * [ConvertFromNanoTDF](./nifi-tdf-processors/src/main/java/io/opentdf/nifi/ConvertFromNanoTDF.java): A NiFi processor that converts NanoTDF formatted FlowFile content to it's plaintext representation

* Controller Services:
  * [OpenTDFControllerService](./nifi-tdf-controller-services-api/src/main/java/io/opentdf/nifi/OpenTDFControllerService.java): A NiFi controller service providing OpenTDF Platform Configuration


#### FlowChart: Generic ZTDF Nifi Flows

```mermaid
---
title: Generic ZTDF NiFi Flows
---
flowchart TD
   a[Nifi Processor]
   b["`**UpdateAttribute**`" Add data policy attributes to FlowFile]
   c["`**ConvertToZTDF**`"]
   d["Process ZTDF"]
   e["Handle Error"]
   f[Nifi Processor]
   g["`**ConvertFromZTDF**`"]
   h[Process Plaintext]
   i[Handle Error]
   a -- success (content = PlainText) --> b
   b -- success (content = PlainText) --> c
   c -- success (content = ZTDF) --> d
   c -- failure --> e
   f -- success (content = ZTDF) --> g
   g -- success (content = PlainText) --> h
   g -- failure --> i
```

#### FlowChart: Generic NanoTDF NiFi Flows
```mermaid
---
title: Generic NanoTDF NiFi Flows
---
flowchart TD
    a[Nifi Processor]
    b["`**UpdateAttribute**`" Add data policy attributes to FlowFile]
    c["`**ConvertToNanoTDF**`"]
    d["Process NanoTDF"]
    e["Handle Error"]
    e2["Handle Max Size Error"]
    f[Nifi Processor]
    g["`**ConvertFromZTDF**`"]
    h[Process Plaintext]
    i[Handle Error]
    a -- success (content = Plaintext) --> b
    b -- success (content = Plaintext)--> c
    c -- success (content = NanoTDF) --> d
    c -- failure --> e
    c -- exceeds_size_limit --> e2
    f -- success (content = NanoTDF) --> g
    g -- success (content = Plaintext) --> h
    g -- failure --> i
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
