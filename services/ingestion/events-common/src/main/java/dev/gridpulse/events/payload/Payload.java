package dev.gridpulse.events.payload;

/**
 * The type-specific part of a {@link dev.gridpulse.events.GridEvent}. Sealed, so a switch over
 * payloads is checked for exhaustiveness when a new event type is added.
 */
public sealed interface Payload
    permits CarbonIntensity, GenerationMix, Demand, DemandForecast, SystemPrice, Weather {}
