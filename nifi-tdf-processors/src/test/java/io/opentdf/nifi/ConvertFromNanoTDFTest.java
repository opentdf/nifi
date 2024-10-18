package io.opentdf.nifi;

import io.opentdf.platform.sdk.NanoTDF;
import io.opentdf.platform.sdk.SDK;
import io.opentdf.platform.sdk.SDKBuilder;
import nl.altindag.ssl.util.KeyStoreUtils;
import org.apache.nifi.ssl.SSLContextService;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConvertFromNanoTDFTest {

    SDK mockSDK;
    NanoTDF mockNanoTDF;

    @BeforeEach
    void setup() {
        mockSDK = mock(SDK.class);
        mockNanoTDF = mock(NanoTDF.class);
    }

    @Test
    void testConvertFromTDF() throws Exception {
        TestRunner runner = TestRunners.newTestRunner(MockRunner.class);
        SDKBuilder mockSDKBuilder = mock(SDKBuilder.class);
        ((MockRunner) runner.getProcessor()).mockNanoTDF = mockNanoTDF;
        ((MockRunner) runner.getProcessor()).mockSDKBuilder = mockSDKBuilder;
        Utils.setupTDFControllerService(runner);

        //add ssl context
        SSLContextService sslContextService = mock(SSLContextService.class);
        File trustStoreFile = Files.createTempFile("trust", "jks").toFile();
        final String TRUST_STORE_PATH = trustStoreFile.getAbsolutePath();
        final String TRUST_STORE_PASSWORD = "foo";
        when(sslContextService.validate(any())).thenReturn(Collections.emptyList());
        when(sslContextService.getTrustStoreFile()).thenReturn(TRUST_STORE_PATH);
        when(sslContextService.getTrustStorePassword()).thenReturn(TRUST_STORE_PASSWORD);

        try (FileOutputStream fos = new FileOutputStream(trustStoreFile)) {
            KeyStoreUtils.createKeyStore().store(fos, TRUST_STORE_PASSWORD.toCharArray());
        }
        when(sslContextService.getIdentifier()).thenReturn(AbstractTDFProcessor.SSL_CONTEXT_SERVICE.getName());
        runner.addControllerService(AbstractTDFProcessor.SSL_CONTEXT_SERVICE.getName(), sslContextService, new HashMap<>());
        runner.enableControllerService(sslContextService);
        runner.setProperty(AbstractTDFProcessor.SSL_CONTEXT_SERVICE, AbstractTDFProcessor.SSL_CONTEXT_SERVICE.getName());


        runner.assertValid();

        SDK.Services mockServices = mock(SDK.Services.class);
        SDK.KAS mockKAS = mock(SDK.KAS.class);
        when(mockSDK.getServices()).thenReturn(mockServices);
        when(mockServices.kas()).thenReturn(mockKAS);
        when(mockSDKBuilder.platformEndpoint("http://platform")).thenReturn(mockSDKBuilder);
        when(mockSDKBuilder.clientSecret("my-client", "123-456")).thenReturn(mockSDKBuilder);
        when(mockSDKBuilder.sslFactoryFromKeyStore(TRUST_STORE_PATH, TRUST_STORE_PASSWORD)).thenReturn(mockSDKBuilder);
        when(mockSDKBuilder.build()).thenReturn(mockSDK);

        ArgumentCaptor<ByteBuffer> byteBufferCapture = ArgumentCaptor.forClass(ByteBuffer.class);
        ArgumentCaptor<OutputStream> outputStreamArgumentCaptor = ArgumentCaptor.forClass(OutputStream.class);
        ArgumentCaptor<SDK.KAS> kasArgumentCaptor = ArgumentCaptor.forClass(SDK.KAS.class);

        doAnswer(invocationOnMock -> {
            ByteBuffer byteBuffer = invocationOnMock.getArgument(0);
            OutputStream outputStream = invocationOnMock.getArgument(1);
            SDK.KAS kas = invocationOnMock.getArgument(2);
            outputStream.write(("Decrypted:" + new String(byteBuffer.array())).getBytes());
            assertNotNull(kas, "KAS is not null");
            assertSame(mockKAS, kas, "Expected KAS passed in");
            return null;
        }).when(mockNanoTDF).readNanoTDF(byteBufferCapture.capture(),
                outputStreamArgumentCaptor.capture(),
                kasArgumentCaptor.capture());
        MockFlowFile messageOne = runner.enqueue("message one".getBytes());
        MockFlowFile messageTwo = runner.enqueue("message two".getBytes());
        runner.run(1);
        List<MockFlowFile> flowFileList =
                runner.getFlowFilesForRelationship(ConvertFromNanoTDF.REL_SUCCESS);
        assertEquals(2, flowFileList.size(), "Two successful flow files");
        assertEquals(1, flowFileList.stream().filter(x -> x.getAttribute("filename").equals(messageOne.getAttribute("filename")))
                .filter(x -> x.getContent().equals("Decrypted:message one")).count());
        assertEquals(1, flowFileList.stream().filter(x -> x.getAttribute("filename").equals(messageTwo.getAttribute("filename")))
                .filter(x -> x.getContent().equals("Decrypted:message two")).count());

    }

    public static class MockRunner extends ConvertFromNanoTDF {
        NanoTDF mockNanoTDF;
        SDKBuilder mockSDKBuilder;

        @Override
        SDKBuilder createSDKBuilder() {
            return mockSDKBuilder;
        }

        @Override
        NanoTDF getNanoTDF() {
            return mockNanoTDF;
        }
    }
}