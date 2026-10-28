package dev.gridpulse.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * Contract tests: the Java model must agree with the shared golden examples in
 * schemas/events/v1/examples, exactly as the Python validator does.
 */
class GoldenExamplesTest {

  private static final EventCodec CODEC = new EventCodec();
  private static final EventValidator VALIDATOR = new EventValidator();

  static Stream<Path> validExamples() throws IOException, URISyntaxException {
    return examples("valid");
  }

  static Stream<Path> invalidExamples() throws IOException, URISyntaxException {
    return examples("invalid");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("validExamples")
  void validExamplePassesSchemaAndRulesAndRoundTripsUnchanged(Path file) throws IOException {
    byte[] json = Files.readAllBytes(file);

    assertThat(VALIDATOR.validate(CODEC.readTree(json))).isEmpty();

    GridEvent event = CODEC.decode(json);
    assertThat(EventRules.violations(event)).isEmpty();

    JsonNode reEncoded = CODEC.readTree(CODEC.encode(event));
    assertThat(VALIDATOR.validate(reEncoded)).isEmpty();
    assertThat(reEncoded.equals(NUMERIC_AWARE, CODEC.readTree(json)))
        .as(
            "re-encoded JSON should equal the original%n  original:   %s%n  re-encoded: %s",
            CODEC.readTree(json), reEncoded)
        .isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("validExamples")
  void createDerivesTheSameIdAsTheGoldenExample(Path file) throws IOException {
    GridEvent golden = CODEC.decode(Files.readAllBytes(file));
    SettlementPeriod settlement =
        golden.settlementPeriod() == null
            ? null
            : new SettlementPeriod(golden.settlementDate(), golden.settlementPeriod());

    GridEvent created =
        GridEvent.create(
            golden.eventType(),
            golden.source(),
            golden.series(),
            golden.region(),
            golden.validFrom(),
            golden.validTo(),
            settlement,
            golden.issuedAt(),
            golden.ingestedAt(),
            golden.payload());

    assertThat(created).isEqualTo(golden);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidExamples")
  void invalidExampleFailsOnlyTheCheckItNames(Path file) throws IOException {
    JsonNode testCase = CODEC.readTree(Files.readAllBytes(file));
    JsonNode event = testCase.get("event");
    String expectedLayer = testCase.get("expect_failure").asString();

    if (expectedLayer.equals("schema")) {
      assertThat(VALIDATOR.validate(event)).isNotEmpty();
      return;
    }

    assertThat(VALIDATOR.validate(event)).as("semantic cases must be schema-valid").isEmpty();
    EventRules.Check expectedCheck =
        EventRules.Check.valueOf(testCase.get("expect_check").asString().toUpperCase(Locale.ROOT));
    assertThat(EventRules.violations(CODEC.decode(event.toString())))
        .containsOnlyKeys(expectedCheck);
  }

  /** Treats 24315 and 24315.0 as equal: the model stores MW as doubles. */
  private static final Comparator<JsonNode> NUMERIC_AWARE =
      (a, b) -> {
        if (a.isNumber() && b.isNumber()) {
          return a.decimalValue().compareTo(b.decimalValue());
        }
        return a.equals(b) ? 0 : 1;
      };

  private static Stream<Path> examples(String kind) throws IOException, URISyntaxException {
    URL dir = GoldenExamplesTest.class.getResource("/gridpulse/examples/v1/" + kind);
    assertThat(dir).as("golden examples are packaged into test resources").isNotNull();
    try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
      return files.filter(p -> p.toString().endsWith(".json")).sorted().toList().stream();
    }
  }
}
