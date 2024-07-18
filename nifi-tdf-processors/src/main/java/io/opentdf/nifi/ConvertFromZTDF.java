package io.opentdf.nifi;

import io.opentdf.platform.sdk.Config;
import io.opentdf.platform.sdk.SDK;
import io.opentdf.platform.sdk.TDF;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.List;


@CapabilityDescription("Decrypts ZTDF flow file content")
@Tags({"ZTDF", "Zero Trust Data Format", "OpenTDF", "Decrypt", "Data Centric Security"})
public class ConvertFromZTDF extends AbstractTDFProcessor {


    @Override
    void processFlowFiles(ProcessContext processContext, ProcessSession processSession, List<FlowFile> flowFiles) throws ProcessException {
        SDK sdk = getTDFSDK(processContext);
        Config.AssertionConfig assertionConfig = new Config.AssertionConfig();
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
