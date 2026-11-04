package de.synthesis.companytour;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Separate from stochastic GTA behaviour settings; HTTP suggestion is explicitly opt-in. */
@Component
@ConfigurationProperties(prefix = "synthesis.company-tour-import")
public class TourImportProperties {
    private boolean geocodingEnabled = false;
    private String geocodingEndpoint = "http://127.0.0.1:8585";
    private int geocodingTimeoutSeconds = 3;
    private int geocodingMaxBatch = 10;
    public boolean isGeocodingEnabled(){return geocodingEnabled;}
    public void setGeocodingEnabled(boolean value){geocodingEnabled=value;}
    public String getGeocodingEndpoint(){return geocodingEndpoint;}
    public void setGeocodingEndpoint(String value){geocodingEndpoint=value;}
    public int getGeocodingTimeoutSeconds(){return geocodingTimeoutSeconds;}
    public void setGeocodingTimeoutSeconds(int value){geocodingTimeoutSeconds=value;}
    public int getGeocodingMaxBatch(){return geocodingMaxBatch;}
    public void setGeocodingMaxBatch(int value){geocodingMaxBatch=value;}
    private boolean llmEnabled = false;
    private boolean sourceAnalysisEnabled = false;
    private String documentPython = "python3";
    private String documentExtractor = "../scripts/extract-tour-document.py";
    public boolean isSourceAnalysisEnabled(){return sourceAnalysisEnabled;}
    public void setSourceAnalysisEnabled(boolean value){sourceAnalysisEnabled=value;}
    public String getDocumentPython(){return documentPython;}
    public void setDocumentPython(String value){documentPython=value;}
    public String getDocumentExtractor(){return documentExtractor;}
    public void setDocumentExtractor(String value){documentExtractor=value;}
    private String endpoint = "http://localhost:11434/v1";
    private String model = "qwen2.5:7b-instruct";
    private int timeoutSeconds = 60;
    public boolean isLlmEnabled() { return llmEnabled; }
    public void setLlmEnabled(boolean value) { llmEnabled = value; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String value) { endpoint = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int value) { timeoutSeconds = value; }
}
