package dev.gridpulse.ingestion.neso;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.gridpulse.ingestion.http.HttpSource;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The NESO Data Portal's historic demand dataset: one CSV per year, found through the portal's CKAN
 * API. Downloads redirect to object storage, which {@link HttpSource} follows.
 */
@Component
public class NesoClient {

  @JsonIgnoreProperties(ignoreUnknown = true)
  record PackageShow(Package result) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Package(List<Resource> resources) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Resource(String id, String name, String url) {}

  private final HttpSource http;
  private final String dataset;

  public NesoClient(RestClient.Builder builder, NesoProperties properties) {
    this.http =
        new HttpSource("NESO Data Portal", builder, properties.baseUrl(), properties.http());
    this.dataset = properties.historicDemandDataset();
  }

  /** The CSV for one year, or empty if NESO hasn't published that year. */
  public Optional<String> historicDemandCsv(int year) {
    String name = "Historic Demand Data " + year;
    List<Resource> resources =
        http.get("/api/3/action/package_show?id=" + dataset, PackageShow.class)
            .result()
            .resources();
    return resources.stream()
        .filter(resource -> resource.name().equals(name))
        .findFirst()
        // The resource URL is absolute; request its path under our base URL (and rate limit).
        .map(resource -> http.get(URI.create(resource.url()).getRawPath(), String.class));
  }
}
