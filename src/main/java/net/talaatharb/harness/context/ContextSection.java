package net.talaatharb.harness.context;

/**
 * How much one contributor added to an assembled context.
 *
 * @param contributor     contributor id
 * @param messages        conversation messages added (system sections count as zero)
 * @param chars           characters added
 * @param estimatedTokens estimated tokens added ({@link TokenEstimator})
 */
public record ContextSection(String contributor, int messages, int chars, int estimatedTokens) {
}
