package io.opentdf.nifi;

import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.processor.exception.ProcessException;

@Tags({"TDF","OpenTDF", "Configuration"})
@CapabilityDescription("Provides A Configuration Service for the OpenTDF SDK")
public interface OpenTDFControllerService extends ControllerService {

    /**
     * Get Configuration
     * @return
     * @throws ProcessException
     */
    public Config getConfig() throws ProcessException;
}
