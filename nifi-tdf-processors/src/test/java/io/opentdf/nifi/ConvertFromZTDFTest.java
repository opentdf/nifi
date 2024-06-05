package io.opentdf.nifi;

import io.opentdf.platform.sdk.SDK;
import io.opentdf.platform.sdk.SDKBuilder;
import io.opentdf.platform.sdk.TDF;
import nl.altindag.ssl.util.KeyStoreUtils;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
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
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.opentdf.nifi.AbstractTDFProcessor.OPENTDF_CONFIG_SERVICE;
import static io.opentdf.nifi.SimpleOpenTDFControllerService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


class ConvertFromZTDFTest {

    SDK mockSDK;
    TDF mockTDF;

    @BeforeEach
    void setup() {
        mockSDK = mock(SDK.class);
        mockTDF = mock(TDF.class);
    }

    void setupTDFControllerService(TestRunner runner) throws Exception {
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

    @Test
    public void testConvertFromTDF() throws Exception {
        TestRunner runner = TestRunners.newTestRunner(MockRunner.class);
        SDKBuilder mockSDKBuilder = mock(SDKBuilder.class);
        ((MockRunner) runner.getProcessor()).mockTDF = mockTDF;
        ((MockRunner) runner.getProcessor()).mockSDKBuilder = mockSDKBuilder;
        setupTDFControllerService(runner);

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

        ArgumentCaptor<SeekableByteChannel> seekableByteChannelArgumentCaptor = ArgumentCaptor.forClass(SeekableByteChannel.class);
        ArgumentCaptor<OutputStream> outputStreamArgumentCaptor = ArgumentCaptor.forClass(OutputStream.class);
        ArgumentCaptor<SDK.KAS> kasArgumentCaptor = ArgumentCaptor.forClass(SDK.KAS.class);

        doAnswer(invocationOnMock -> {
            SeekableInMemoryByteChannel seekableByteChannel = invocationOnMock.getArgument(0);
            OutputStream outputStream = invocationOnMock.getArgument(1);
            SDK.KAS kas = invocationOnMock.getArgument(2);
            outputStream.write(("Decrypted:" + new String(seekableByteChannel.array())).getBytes());
            assertNotNull(kas, "KAS is not null");
            assertSame(mockKAS, kas, "Expected KAS passed in");
            return null;
        }).when(mockTDF).loadTDF(seekableByteChannelArgumentCaptor.capture(),
                outputStreamArgumentCaptor.capture(),
                kasArgumentCaptor.capture());
        MockFlowFile messageOne = runner.enqueue("message one".getBytes());
        MockFlowFile messageTwo = runner.enqueue("message two".getBytes());
        runner.run(1);
        List<MockFlowFile> flowFileList =
                runner.getFlowFilesForRelationship(ConvertFromZTDF.REL_SUCCESS);
        assertEquals(2, flowFileList.size(), "Two successful flow files");
        assertEquals(1, flowFileList.stream().filter(x -> x.getAttribute("filename").equals(messageOne.getAttribute("filename")))
                .filter(x -> x.getContent().equals("Decrypted:message one")).count());
        assertEquals(1, flowFileList.stream().filter(x -> x.getAttribute("filename").equals(messageTwo.getAttribute("filename")))
                .filter(x -> x.getContent().equals("Decrypted:message two")).count());

    }

    public static class MockRunner extends ConvertFromZTDF {
        TDF mockTDF;
        SDKBuilder mockSDKBuilder;

        @Override
        SDKBuilder createSDKBuilder() {
            return mockSDKBuilder;
        }

        @Override
        TDF getTDF() {
            return mockTDF;
        }
    }
}