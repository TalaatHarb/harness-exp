package net.talaatharb.harness.speech;

import java.util.Objects;

/** Mono PCM audio held as normalized floats in [-1, 1]. */
public record AudioClip(float[] samples, int sampleRate) {
    public AudioClip {
        Objects.requireNonNull(samples, "samples");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("Sample rate must be positive: " + sampleRate);
        }
    }

    public double durationSeconds() {
        return samples.length / (double) sampleRate;
    }

    public boolean isEmpty() {
        return samples.length == 0;
    }

    /** Returns this clip at the requested rate, resampling only when needed. */
    public AudioClip atSampleRate(int targetRate) {
        return targetRate == sampleRate ? this : Resampler.resample(this, targetRate);
    }

    /** Returns at most {@code seconds} of audio from the start of the clip. */
    public AudioClip truncate(double seconds) {
        int max = (int) Math.min(samples.length, Math.round(seconds * sampleRate));
        if (max == samples.length) {
            return this;
        }
        float[] copy = new float[max];
        System.arraycopy(samples, 0, copy, 0, max);
        return new AudioClip(copy, sampleRate);
    }
}
