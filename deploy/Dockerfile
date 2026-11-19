# Wrap the upstream NiFi image without changing its user or entrypoint.
ARG NIFI_BASE_IMAGE=opentdf-nifi:base
FROM ${NIFI_BASE_IMAGE}

# CVE-2026-114938 (Java4Shell): disable the vulnerable Base64 intrinsic.
# Keep this mitigation until the runtime image is verified to contain the fix.
ENV JAVA_TOOL_OPTIONS="-XX:-UseBASE64Intrinsics"
