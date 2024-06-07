package io.opentdf.nifi;

import org.apache.nifi.util.TestRunner;

import java.util.HashMap;
import java.util.Map;

import static io.opentdf.nifi.AbstractTDFProcessor.OPENTDF_CONFIG_SERVICE;
import static io.opentdf.nifi.SimpleOpenTDFControllerService.*;
import static io.opentdf.nifi.SimpleOpenTDFControllerService.USE_PLAINTEXT;

public class Utils {

    static void setupTDFControllerService(TestRunner runner) throws Exception {
            SimpleOpenTDFControllerService tdfControllerService = new SimpleOpenTDFControllerService();
            Map<String, String> controllerPropertyMap = new HashMap<>();
            controllerPropertyMap.put(PLATFORM_ENDPOINT.getName(), "http://platform");
            controllerPropertyMap.put(CLIENT_ID.getName(), "my-client");
            controllerPropertyMap.put(CLIENT_SECRET.getName(), "123-456");
            controllerPropertyMap.put(USE_PLAINTEXT.getName(), "false");
            runner.addControllerService(OPENTDF_CONFIG_SERVICE.getName(), tdfControllerService, controllerPropertyMap);
            runner.enableControllerService(tdfControllerService);
            runner.assertValid(tdfControllerService);
            runner.setProperty(OPENTDF_CONFIG_SERVICE.getName(), OPENTDF_CONFIG_SERVICE.getName());
    }


}
