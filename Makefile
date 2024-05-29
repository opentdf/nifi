
.PHONY: compose-package
compose-package: nar-build
	@echo "package for docker compose"
	rm -rf deploy/extensions/*.nar
	cp nifi-tdf-nar/target/*.nar deploy/extensions
	cp nifi-tdf-controller-services-api-nar/target/*.nar deploy/extensions

.PHONY: nar-build
nar-build:
	@echo "Build NARs"
	mvn clean package -s settings.xml