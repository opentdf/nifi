package io.opentdf.nifi;


public class Config {

    private boolean usePlainText;
    private String platformEndpoint;
    private String clientId;
    private String clientSecret;

    public Config(String platformEndpoint, String clientId, String clientSecret) {
        this();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.usePlainText = false;
    }

    public Config() {
        this.usePlainText = false;
    }

    public boolean isUsePlainText() {
        return usePlainText;
    }

    public void setUsePlainText(boolean usePlainText) {
        this.usePlainText = usePlainText;
    }

    public String getPlatformEndpoint() {
        return platformEndpoint;
    }

    public void setPlatformEndpoint(String platformEndpoint) {
        this.platformEndpoint = platformEndpoint;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }
}
