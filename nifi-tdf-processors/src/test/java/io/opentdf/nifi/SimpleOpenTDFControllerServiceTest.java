package io.opentdf.nifi;

import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.components.PropertyValue;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimpleOpenTDFControllerServiceTest {

    @Test
    void testEnabledWithValidValues() throws InitializationException {
        SimpleOpenTDFControllerService service = new SimpleOpenTDFControllerService();

        ConfigurationContext context = Mockito.mock(ConfigurationContext.class);
        PropertyValue clientID = Mockito.mock(PropertyValue.class);
        PropertyValue clientSecret = Mockito.mock(PropertyValue.class);
        PropertyValue platformEndpoint = Mockito.mock(PropertyValue.class);
        PropertyValue usePlainText = Mockito.mock(PropertyValue.class);

        Mockito.when(context.getProperty(SimpleOpenTDFControllerService.CLIENT_ID)).thenReturn(clientID);
        Mockito.when(context.getProperty(SimpleOpenTDFControllerService.CLIENT_SECRET)).thenReturn(clientSecret);
        Mockito.when(context.getProperty(SimpleOpenTDFControllerService.PLATFORM_ENDPOINT)).thenReturn(platformEndpoint);
        Mockito.when(context.getProperty(SimpleOpenTDFControllerService.USE_PLAINTEXT)).thenReturn(usePlainText);

        Mockito.when(clientID.getValue()).thenReturn("Valid client ID");
        Mockito.when(clientSecret.getValue()).thenReturn("Valid client Secret");
        Mockito.when(platformEndpoint.getValue()).thenReturn("Valid platform endpoint");
        Mockito.when(usePlainText.asBoolean()).thenReturn(true);

        service.enabled(context);

        assertNotNull(service.getConfig());
        assertEquals("Valid client ID", service.getConfig().getClientId());
        assertEquals("Valid client Secret", service.getConfig().getClientSecret());
        assertEquals("Valid platform endpoint", service.getConfig().getPlatformEndpoint());
    }

    @Test
    void testEnabledWithInvalidValues() {
        SimpleOpenTDFControllerService service = new SimpleOpenTDFControllerService();

        ConfigurationContext context = Mockito.mock(ConfigurationContext.class);

        // Mock property values with invalid data
        PropertyValue clientID = Mockito.mock(PropertyValue.class);
        PropertyValue clientSecret = Mockito.mock(PropertyValue.class);
        PropertyValue platformEndpoint = Mockito.mock(PropertyValue.class);
        PropertyValue usePlainText = Mockito.mock(PropertyValue.class);

        // Return null for all properties to simulate invalid values
        Mockito.when(context.getProperty(SimpleOpenTDFControllerService.CLIENT_ID)).thenReturn(clientID);
        Mockito.when(context.getProperty(SimpleOpenTDFControllerService.CLIENT_SECRET)).thenReturn(clientSecret);
        Mockito.when(context.getProperty(SimpleOpenTDFControllerService.PLATFORM_ENDPOINT)).thenReturn(platformEndpoint);
        Mockito.when(context.getProperty(SimpleOpenTDFControllerService.USE_PLAINTEXT)).thenReturn(usePlainText);

        Mockito.when(clientID.getValue()).thenReturn(null);
        Mockito.when(clientSecret.getValue()).thenReturn(null);
        Mockito.when(platformEndpoint.getValue()).thenReturn(null);
        Mockito.when(usePlainText.asBoolean()).thenReturn(null);

        // Ensure that the enabled method throws an InitializationException
        assertThrows(InitializationException.class, () -> service.enabled(context));
    }
}