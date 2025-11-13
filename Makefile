NIFI_VERSION?=1.28.1

nifi-image:
	wget https://raw.githubusercontent.com/apache/nifi/refs/tags/rel/nifi-$(NIFI_VERSION)/nifi-docker/dockerhub/Dockerfile
	curl -L https://github.com/apache/nifi/archive/refs/tags/rel/nifi-$(NIFI_VERSION).tar.gz -o nifi-$(NIFI_VERSION).tar.gz
	tar -xzf nifi-$(NIFI_VERSION).tar.gz
	docker build -t opentdf-nifi:local -f ./nifi-rel-nifi-$(NIFI_VERSION)/nifi-docker/dockerhub/Dockerfile --build-arg IMAGE_TAG=17-jre --build-arg BASE_URL=https://dlcdn.apache.org --build-arg NIFI_VERSION=$(NIFI_VERSION) --build-arg IMAGE_NAME=public.ecr.aws/docker/library/eclipse-temurin ./nifi-rel-nifi-$(NIFI_VERSION)/nifi-docker/dockerhub


.PHONY: compose-package
compose-package: nar-build
	@echo "package for docker compose"
	mkdir -p deploy/extensions
	rm -rf deploy/extensions/*.nar
	cp nifi-tdf-nar/target/*.nar deploy/extensions
	cp nifi-tdf-controller-services-api-nar/target/*.nar deploy/extensions

.PHONY: truststore-create
truststore-create:
	@echo "Build Truststore from *.crt in ./deploy/truststore"
	cd ./deploy && ./build_truststore.sh

.PHONY: nar-build
nar-build:
	@echo "Build NARs"
	mvn clean package -s settings.xml