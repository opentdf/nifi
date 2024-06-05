package io.opentdf.nifi;

import io.opentdf.platform.sdk.SDK;
import io.opentdf.platform.sdk.TDF;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;


@CapabilityDescription("Decrypts ZTDF flow file content")
@Tags({"ZTDF", "Zero Trust Data Format", "OpenTDF", "Decrypt", "Data Centric Security"})
public class ConvertFromZTDF extends AbstractTDFProcessor {

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return Collections.unmodifiableList(Arrays.asList(SSL_CONTEXT_SERVICE, OPENTDF_CONFIG_SERVICE, FLOWFILE_PULL_SIZE));
    }


    @Override
    public void onTrigger(ProcessContext processContext, ProcessSession processSession) throws ProcessException {
        List<FlowFile> flowFiles = processSession.get(processContext.getProperty(FLOWFILE_PULL_SIZE).asInteger());
        if (!flowFiles.isEmpty()) {
            SDK sdk = getTDFSDK(processContext);
            for (FlowFile flowFile : flowFiles) {
                try {
                    try (SeekableByteChannel seekableByteChannel = new SeekableInMemoryByteChannel(readEntireFlowFile(flowFile, processSession))) {
                        FlowFile updatedFlowFile = processSession.write(flowFile, outputStream -> {
                            try {
                                getTDF().loadTDF(seekableByteChannel, outputStream, sdk.getServices().kas());
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

    TDF getTDF() {
        return new TDF();
    }
}
