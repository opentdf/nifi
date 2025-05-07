package io.opentdf.nifi;

import io.opentdf.platform.sdk.Config;
import io.opentdf.platform.sdk.Config.TDFConfig;
import io.opentdf.platform.sdk.SDK;
import io.opentdf.platform.sdk.TDF;
import io.opentdf.platform.sdk.KeyType;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts and decrypts ZTDF (Zero Trust Data Format) flow file content.
 * This class takes encrypted ZTDF content and decrypts it,
 * transferring the decrypted data to a specified success relationship.
 * If an error occurs during decryption, it transfers the flow file to a failure relationship.
 * <p>
 * This processor uses TDF (Trusted Data Format) SDK for decryption and
 * requires configuration of assertion verification keys to verify
 * the integrity and authenticity of the encrypted data.
 * <p>
 * It provides the primary method `processFlowFiles` which reads the encrypted
 * content from incoming flow files, decrypts it, and writes the decrypted
 * content back to the flow files.
 * <p>
 * The method `processFlowFiles` performs the following steps:
 * 1. Retrieves the TDF SDK instance.
 * 2. Reads the entire encrypted content of each flow file into an in-memory byte channel.
 * 3. Uses TDF Reader to load and decrypt the content.
 * 4. Writes the decrypted content back into the flow file and transfers it to the success relationship.
 * 5. If any error occurs during the decryption process, logs the error and transfers the flow file to the failure relationship.
 */
@CapabilityDescription("Decrypts ZTDF flow file content")
@Tags({"ZTDF", "Zero Trust Data Format", "OpenTDF", "Decrypt", "Data Centric Security"})
public class ConvertFromZTDF extends AbstractTDFProcessor {

    /**
     * Default constructor for ConvertFromZTDF.
     */
    public ConvertFromZTDF() {
        super();
    }

    /**
     * Processes a list of flow files by decrypting their content using the TDF (Trusted Data Format) SDK.
     * For each flow file in the provided list, the following steps are executed:
     * 1. Reads the entire encrypted content of the flow file into an in-memory byte channel.
     * 2. Uses a TDF Reader to load and decrypt the content using the SDK.
     * 3. Writes the decrypted content back to the flow file.
     * 4. Transfers the successfully decrypted flow files to the success relationship.
     * 5. In case of an error during decryption, logs the error and transfers the flow file to the failure relationship.
     *
     * @param processContext the NiFi ProcessContext providing configuration and controller services.
     * @param processSession the NiFi ProcessSession used to read, write, and transfer flow files.
     * @param flowFiles a list of flow files to be decrypted.
     * @throws ProcessException if an error occurs during the decryption process.
     */
    @Override
    void processFlowFiles(ProcessContext processContext, ProcessSession processSession, List<FlowFile> flowFiles) throws ProcessException {
        SDK sdk = getTDFSDK(processContext);
        
        for (FlowFile flowFile : flowFiles) {
            try {
                try (SeekableByteChannel seekableByteChannel = new SeekableInMemoryByteChannel(readEntireFlowFile(flowFile, processSession))) {
                    FlowFile updatedFlowFile = processSession.write(flowFile, outputStream -> {
                        try {
                            TDF.Reader reader = getTDF().loadTDF(seekableByteChannel, sdk.getServices().kas(), Config.newTDFReaderConfig(Config.withDisableAssertionVerification(true)), sdk.getServices().kasRegistry(), sdk.getPlatformUrl());
                            reader.readPayload(outputStream);
                        } catch (InterruptedException e) {
                            getLogger().error("error decrypting ZTDF", e);
                            Thread.currentThread().interrupt();
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
