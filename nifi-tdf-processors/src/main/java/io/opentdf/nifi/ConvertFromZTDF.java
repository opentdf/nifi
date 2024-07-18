package io.opentdf.nifi;

import io.opentdf.platform.sdk.Config;
import io.opentdf.platform.sdk.SDK;
import io.opentdf.platform.sdk.TDF;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


@CapabilityDescription("Decrypts ZTDF flow file content")
@Tags({"ZTDF", "Zero Trust Data Format", "OpenTDF", "Decrypt", "Data Centric Security"})
public class ConvertFromZTDF extends AbstractTDFProcessor {

    public static final PropertyDescriptor VERIFY_ASSERTIONS = new org.apache.nifi.components.PropertyDescriptor.Builder()
            .name("Verify Assertions")
            .description("Verify assertions")
            .required(false)
            .defaultValue("false")
            .allowableValues("true", "false")
            .build();

    public static final PropertyDescriptor ASSERTION_PUBLIC_KEY = new org.apache.nifi.components.PropertyDescriptor.Builder()
            .name("Assertion Verification Key")
            .description("path to the public key for verifying assertions")
            .required(true)
            .dependsOn(VERIFY_ASSERTIONS, new AllowableValue("true"))
            .build();


    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        List<PropertyDescriptor> propertyDescriptors = new ArrayList<>(super.getSupportedPropertyDescriptors());
        propertyDescriptors.add(VERIFY_ASSERTIONS);
        propertyDescriptors.add(ASSERTION_PUBLIC_KEY);
        return Collections.unmodifiableList(propertyDescriptors);
    }

    @Override
    void processFlowFiles(ProcessContext processContext, ProcessSession processSession, List<FlowFile> flowFiles) throws ProcessException {
        SDK sdk = getTDFSDK(processContext);
        Config.AssertionConfig assertionConfig = getAssertionConfig(processContext);
        for (FlowFile flowFile : flowFiles) {
            try {
                try (SeekableByteChannel seekableByteChannel = new SeekableInMemoryByteChannel(readEntireFlowFile(flowFile, processSession))) {
                    FlowFile updatedFlowFile = processSession.write(flowFile, outputStream -> {
                        try {
                            TDF.Reader reader = getTDF().loadTDF(seekableByteChannel, assertionConfig, sdk.getServices().kas());
                            reader.readPayload(outputStream);
                        } catch (Exception e) {
                            getLogger().error("error decrypting ZTDF", e);
                            throw new IOException(e);
                        }
                    });
                    processSession.transfer(updatedFlowFile, REL_SUCCESS);
                }
            } catch (Exception e) {
                getLogger().error(flowFile.getId() + ": error decrypting flowfile", e);
                processSession.transfer(flowFile, REL_FAILURE);
            }
        }
    }
}
