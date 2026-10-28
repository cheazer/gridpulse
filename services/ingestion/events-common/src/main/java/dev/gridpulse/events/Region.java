package dev.gridpulse.events;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * {@code GB} for national data, otherwise a Carbon Intensity API region (DNO areas and nations).
 * The slug is also the Kafka message key, so all events for one region stay in order.
 */
public enum Region {
  GB("GB"),
  NORTH_SCOTLAND("north-scotland"),
  SOUTH_SCOTLAND("south-scotland"),
  NORTH_WEST_ENGLAND("north-west-england"),
  NORTH_EAST_ENGLAND("north-east-england"),
  YORKSHIRE("yorkshire"),
  NORTH_WALES_MERSEYSIDE("north-wales-merseyside"),
  SOUTH_WALES("south-wales"),
  WEST_MIDLANDS("west-midlands"),
  EAST_MIDLANDS("east-midlands"),
  EAST_ENGLAND("east-england"),
  SOUTH_WEST_ENGLAND("south-west-england"),
  SOUTH_ENGLAND("south-england"),
  LONDON("london"),
  SOUTH_EAST_ENGLAND("south-east-england"),
  ENGLAND("england"),
  SCOTLAND("scotland"),
  WALES("wales");

  private final String slug;

  Region(String slug) {
    this.slug = slug;
  }

  @JsonValue
  public String slug() {
    return slug;
  }
}
