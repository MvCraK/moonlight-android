package com.limelight.binding.audio;

import com.limelight.binding.input.ControllerHandler;

import java.util.Arrays;

public final class ControllerAudioHapticsController {
    private static final int TARGET_SAMPLE_RATE = 3000;
    private static final int POW_LUT_SIZE = 256;
    private static final float[] POW_062_LUT = buildPowLut(0.62);
    private static final float[] POW_080_LUT = buildPowLut(0.80);

    private static float[] buildPowLut(double exponent) {
        float[] lut = new float[POW_LUT_SIZE + 1];
        for (int i = 0; i <= POW_LUT_SIZE; i++) {
            lut[i] = (float) Math.pow(i / (double) POW_LUT_SIZE, exponent);
        }
        return lut;
    }

    private static float lookupPow(float[] lut, float value) {
        if (value <= 0.0f) {
            return 0.0f;
        }
        if (value >= 1.0f) {
            return 1.0f;
        }
        float scaled = value * POW_LUT_SIZE;
        int index = (int) scaled;
        float frac = scaled - index;
        return lut[index] + frac * (lut[index + 1] - lut[index]);
    }

    private static final float DS5_LOWPASS_CUTOFF_HZ = 260.0f;
    private static final float DS5_VOICE_LOWPASS_CUTOFF_HZ = 1800.0f;
    private static final float DS5_NOISE_GATE = 0.0035f;
    private static final float DS5_RELEASE_FLOOR = 0.0015f;
    private static final float DS5_DRY_BLEND = 0.45f;
    private static final float DS5_TRANSIENT_BLEND = 0.28f;
    private static final float DS5_STRENGTH_MULTIPLIER = 2.5f;
    private static final float DS5_GAIN_MULTIPLIER = 2.35f;
    private static final float DS5_SUSTAIN_FLOOR = 0.065f;

    private static final float STANDARD_LOWPASS_CUTOFF_HZ = 200.0f;
    private static final float STANDARD_VOICE_LOWPASS_CUTOFF_HZ = 1600.0f;
    private static final float STANDARD_NOISE_GATE = 0.0030f;
    private static final float STANDARD_RELEASE_FLOOR = 0.0010f;
    private static final float STANDARD_TRANSIENT_BLEND = 0.18f;
    private static final float STANDARD_SUSTAIN_FLOOR = 0.060f;
    private static final float STANDARD_LOW_MOTOR_MULTIPLIER = 2.45f;
    private static final float STANDARD_HIGH_MOTOR_MULTIPLIER = 1.35f;
    private static final short STANDARD_STOP_THRESHOLD = 1100;

    private final ControllerHandler controllerHandler;
    private boolean enabled;
    private int strengthPercent;
    private String voiceFilterMode;
    private boolean cachedHighVoiceFilter;
    private float cachedDs5Alpha;
    private float cachedDs5VoiceAlpha;
    private float cachedDs5Suppression;
    private float cachedDs5StrengthScale;
    private float cachedStandardAlpha;
    private float cachedStandardVoiceAlpha;
    private float cachedStandardSuppression;
    private float cachedStandardStrengthScale;

    private int channelCount;
    private int sampleRate;

    private byte[] ds5ScratchBuffer = new byte[128];
    private int ds5ScratchLength;
    private float lastStandardBassLevel;
    private float lastStandardTransientLevel;

    private int ds5ResampleAccumulator;
    private float ds5LeftLowPassState;
    private float ds5RightLowPassState;
    private float ds5LeftVoiceLowPassState;
    private float ds5RightVoiceLowPassState;
    private float ds5SmoothedLevel;

    private float standardLeftLowPassState;
    private float standardRightLowPassState;
    private float standardLeftVoiceLowPassState;
    private float standardRightVoiceLowPassState;
    private float standardSmoothedLevel;
    private short lastStandardLowMotor;
    private short lastStandardHighMotor;

    public ControllerAudioHapticsController(ControllerHandler controllerHandler,
                                            boolean enabled, int strengthPercent,
                                            String voiceFilterMode) {
        this.controllerHandler = controllerHandler;
        this.enabled = enabled && controllerHandler != null;
        this.strengthPercent = Math.max(0, strengthPercent);
        this.voiceFilterMode = normalizeVoiceFilterMode(voiceFilterMode);
        updateCachedParameters();
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized void configure(int channelCount, int sampleRate) {
        this.channelCount = Math.max(1, channelCount);
        this.sampleRate = Math.max(1, sampleRate);
        updateCachedParameters();
        resetState();
    }

    public synchronized void setSettings(boolean enabled, int strengthPercent, String voiceFilterMode) {
        this.enabled = enabled && controllerHandler != null;
        this.strengthPercent = Math.max(0, strengthPercent);
        this.voiceFilterMode = normalizeVoiceFilterMode(voiceFilterMode);
        updateCachedParameters();
    }

    private void updateCachedParameters() {
        this.cachedHighVoiceFilter = AudioHapticsController.VOICE_FILTER_HIGH.equals(voiceFilterMode);
        this.cachedDs5Suppression = getDs5VoiceSuppressionStrength();
        this.cachedDs5StrengthScale = Math.min(2.75f,
                (strengthPercent / 100.0f) * DS5_STRENGTH_MULTIPLIER);
        this.cachedStandardSuppression = getStandardVoiceSuppressionStrength();
        this.cachedStandardStrengthScale = Math.max(0.0f, strengthPercent / 100.0f);
        if (sampleRate > 0) {
            this.cachedDs5Alpha = getLowPassAlpha(DS5_LOWPASS_CUTOFF_HZ);
            this.cachedDs5VoiceAlpha = getLowPassAlpha(DS5_VOICE_LOWPASS_CUTOFF_HZ);
            this.cachedStandardAlpha = getLowPassAlpha(STANDARD_LOWPASS_CUTOFF_HZ);
            this.cachedStandardVoiceAlpha = getLowPassAlpha(STANDARD_VOICE_LOWPASS_CUTOFF_HZ);
        }
    }

    public synchronized void onAudioFrame(short[] audioData) {
        if (!enabled || controllerHandler == null || audioData == null ||
                audioData.length < channelCount || channelCount <= 0 || sampleRate <= 0) {
            return;
        }

        float ds5Alpha = cachedDs5Alpha;
        float ds5VoiceAlpha = cachedDs5VoiceAlpha;
        float ds5Suppression = cachedDs5Suppression;
        float ds5StrengthScale = cachedDs5StrengthScale;

        float standardAlpha = cachedStandardAlpha;
        float standardVoiceAlpha = cachedStandardVoiceAlpha;
        float standardSuppression = cachedStandardSuppression;
        float standardStrengthScale = cachedStandardStrengthScale;

        int frames = audioData.length / channelCount;
        if (frames <= 0) {
            return;
        }

        ds5ScratchLength = 0;
        float standardBassPeak = 0.0f;
        float standardTransientPeak = 0.0f;
        int rightChannelIndex = Math.min(1, channelCount - 1);

        for (int frameIndex = 0; frameIndex < frames; frameIndex++) {
            int base = frameIndex * channelCount;
            float leftSample = audioData[base] * (1.0f / 32768.0f);
            float rightSample = audioData[base + rightChannelIndex] * (1.0f / 32768.0f);

            processDs5Sample(leftSample, rightSample, ds5Alpha, ds5VoiceAlpha, ds5Suppression, ds5StrengthScale);
            processStandardSample(leftSample, rightSample,
                    standardAlpha, standardVoiceAlpha, standardSuppression, standardStrengthScale);
            if (lastStandardBassLevel > standardBassPeak) {
                standardBassPeak = lastStandardBassLevel;
            }
            if (lastStandardTransientLevel > standardTransientPeak) {
                standardTransientPeak = lastStandardTransientLevel;
            }
        }

        short standardLowMotor = toMotorValue(standardBassPeak * STANDARD_LOW_MOTOR_MULTIPLIER);
        short standardHighMotor = toMotorValue(standardTransientPeak * STANDARD_HIGH_MOTOR_MULTIPLIER);
        if (standardLowMotor < STANDARD_STOP_THRESHOLD) {
            standardLowMotor = 0;
        }
        if (standardHighMotor < STANDARD_STOP_THRESHOLD) {
            standardHighMotor = 0;
        }
        if (standardLowMotor != lastStandardLowMotor || standardHighMotor != lastStandardHighMotor) {
            controllerHandler.handleStandardControllerAudioHaptics(standardLowMotor, standardHighMotor);
            lastStandardLowMotor = standardLowMotor;
            lastStandardHighMotor = standardHighMotor;
        }

        if (ds5ScratchLength > 0) {
            byte[] ds5Frame = Arrays.copyOf(ds5ScratchBuffer, ds5ScratchLength);
            controllerHandler.handleControllerAdvancedAudioHapticsFrame(ds5Frame,
                    Math.min(2.75f, (strengthPercent / 100.0f) * DS5_GAIN_MULTIPLIER));
        }

    }

    public synchronized void stop() {
        if (lastStandardLowMotor != 0 || lastStandardHighMotor != 0) {
            controllerHandler.handleStandardControllerAudioHaptics((short) 0, (short) 0);
        }
        resetState();
    }

    private void appendDs5StereoSample(short leftOut, short rightOut) {
        if (ds5ScratchLength + 4 > ds5ScratchBuffer.length) {
            ds5ScratchBuffer = Arrays.copyOf(ds5ScratchBuffer, ds5ScratchBuffer.length * 2);
        }
        ds5ScratchBuffer[ds5ScratchLength++] = (byte) (leftOut & 0xFF);
        ds5ScratchBuffer[ds5ScratchLength++] = (byte) ((leftOut >> 8) & 0xFF);
        ds5ScratchBuffer[ds5ScratchLength++] = (byte) (rightOut & 0xFF);
        ds5ScratchBuffer[ds5ScratchLength++] = (byte) ((rightOut >> 8) & 0xFF);
    }

    private void processDs5Sample(float leftSample, float rightSample,
                                  float alpha, float voiceAlpha, float suppression,
                                  float strengthScale) {
        ds5LeftLowPassState += alpha * (leftSample - ds5LeftLowPassState);
        ds5RightLowPassState += alpha * (rightSample - ds5RightLowPassState);
        ds5LeftVoiceLowPassState += voiceAlpha * (leftSample - ds5LeftVoiceLowPassState);
        ds5RightVoiceLowPassState += voiceAlpha * (rightSample - ds5RightVoiceLowPassState);

        float leftVoiceResidual = Math.abs(ds5LeftVoiceLowPassState - ds5LeftLowPassState);
        float rightVoiceResidual = Math.abs(ds5RightVoiceLowPassState - ds5RightLowPassState);
        float leftTransient = (leftSample - ds5LeftLowPassState) * DS5_TRANSIENT_BLEND;
        float rightTransient = (rightSample - ds5RightLowPassState) * DS5_TRANSIENT_BLEND;
        float leftBody = (ds5LeftLowPassState * (1.0f - DS5_DRY_BLEND)) + (leftSample * DS5_DRY_BLEND) + leftTransient;
        float rightBody = (ds5RightLowPassState * (1.0f - DS5_DRY_BLEND)) + (rightSample * DS5_DRY_BLEND) + rightTransient;
        float leftFiltered = applyVoiceAttenuation(leftBody, leftVoiceResidual, suppression);
        float rightFiltered = applyVoiceAttenuation(rightBody, rightVoiceResidual, suppression);

        float envelopeInput = Math.max(Math.abs(leftFiltered), Math.abs(rightFiltered)) +
                (0.35f * Math.max(Math.abs(leftTransient), Math.abs(rightTransient)));
        float targetLevel = Math.max(0.0f, (envelopeInput - DS5_NOISE_GATE) / (0.12f - DS5_NOISE_GATE));
        float attack = targetLevel > ds5SmoothedLevel ? 0.62f : 0.10f;
        ds5SmoothedLevel += attack * (targetLevel - ds5SmoothedLevel);
        if (envelopeInput > (DS5_NOISE_GATE * 1.4f) &&
                ds5SmoothedLevel > DS5_RELEASE_FLOOR && targetLevel < DS5_SUSTAIN_FLOOR) {
            ds5SmoothedLevel = Math.max(ds5SmoothedLevel, DS5_SUSTAIN_FLOOR);
        }

        ds5ResampleAccumulator += TARGET_SAMPLE_RATE;
        if (ds5ResampleAccumulator >= sampleRate) {
            float levelScale = ds5SmoothedLevel >= DS5_RELEASE_FLOOR ?
                    lookupPow(POW_062_LUT, ds5SmoothedLevel) : 0.0f;
            float gain = levelScale * strengthScale;
            short leftOut = floatToShort(leftFiltered * gain);
            short rightOut = floatToShort(rightFiltered * gain);
            while (ds5ResampleAccumulator >= sampleRate) {
                appendDs5StereoSample(leftOut, rightOut);
                ds5ResampleAccumulator -= sampleRate;
            }
        }
    }

    private void processStandardSample(float leftSample, float rightSample,
                                       float alpha, float voiceAlpha, float suppression,
                                       float strengthScale) {
        standardLeftLowPassState += alpha * (leftSample - standardLeftLowPassState);
        standardRightLowPassState += alpha * (rightSample - standardRightLowPassState);
        standardLeftVoiceLowPassState += voiceAlpha * (leftSample - standardLeftVoiceLowPassState);
        standardRightVoiceLowPassState += voiceAlpha * (rightSample - standardRightVoiceLowPassState);

        float leftVoiceResidual = Math.abs(standardLeftVoiceLowPassState - standardLeftLowPassState);
        float rightVoiceResidual = Math.abs(standardRightVoiceLowPassState - standardRightLowPassState);
        float leftTransient = Math.abs(leftSample - standardLeftLowPassState) * STANDARD_TRANSIENT_BLEND;
        float rightTransient = Math.abs(rightSample - standardRightLowPassState) * STANDARD_TRANSIENT_BLEND;
        float leftFiltered = applyVoiceFilter(standardLeftLowPassState, leftVoiceResidual, suppression);
        float rightFiltered = applyVoiceFilter(standardRightLowPassState, rightVoiceResidual, suppression);

        float bassPresence = 0.5f * (Math.abs(leftFiltered) + Math.abs(rightFiltered));
        float transientPresence = Math.max(leftTransient, rightTransient);
        float envelopeInput = bassPresence + (0.40f * transientPresence);
        float targetLevel = Math.max(0.0f, (envelopeInput - STANDARD_NOISE_GATE) / (0.10f - STANDARD_NOISE_GATE));
        float attack = targetLevel > standardSmoothedLevel ? 0.38f : 0.14f;
        standardSmoothedLevel += attack * (targetLevel - standardSmoothedLevel);
        if (bassPresence > (STANDARD_NOISE_GATE * 1.2f) &&
                standardSmoothedLevel > STANDARD_RELEASE_FLOOR &&
                targetLevel < STANDARD_SUSTAIN_FLOOR) {
            standardSmoothedLevel = Math.max(standardSmoothedLevel, STANDARD_SUSTAIN_FLOOR);
        }

        float levelScale = standardSmoothedLevel >= STANDARD_RELEASE_FLOOR ?
                lookupPow(POW_080_LUT, standardSmoothedLevel) : 0.0f;
        float gain = levelScale * strengthScale;
        lastStandardBassLevel = bassPresence * gain;
        lastStandardTransientLevel = transientPresence * gain;
    }

    private void resetState() {
        ds5ResampleAccumulator = 0;
        ds5LeftLowPassState = 0.0f;
        ds5RightLowPassState = 0.0f;
        ds5LeftVoiceLowPassState = 0.0f;
        ds5RightVoiceLowPassState = 0.0f;
        ds5SmoothedLevel = 0.0f;

        standardLeftLowPassState = 0.0f;
        standardRightLowPassState = 0.0f;
        standardLeftVoiceLowPassState = 0.0f;
        standardRightVoiceLowPassState = 0.0f;
        standardSmoothedLevel = 0.0f;
        lastStandardLowMotor = 0;
        lastStandardHighMotor = 0;

    }

    private float extractChannelSample(short[] audioData, int base, int channelIndex) {
        if (channelCount == 1) {
            return audioData[base] / 32768.0f;
        }

        int resolvedIndex = Math.min(channelIndex, channelCount - 1);
        return audioData[base + resolvedIndex] / 32768.0f;
    }

    private float getLowPassAlpha(float cutoffHz) {
        float omega = (float) (2.0 * Math.PI * cutoffHz / sampleRate);
        return Math.min(1.0f, Math.max(0.005f, omega));
    }

    private float applyVoiceFilter(float bassSample, float voiceResidual, float suppression) {
        if (suppression <= 0.0f) {
            return bassSample;
        }

        float filtered = Math.max(0.0f, bassSample - (voiceResidual * suppression));

        if (cachedHighVoiceFilter) {
            float voiceDominance = voiceResidual / Math.max(0.0025f, Math.abs(bassSample) + voiceResidual);
            if (voiceDominance > 0.40f) {
                float attenuation = Math.max(0.08f, 1.0f - ((voiceDominance - 0.40f) * 2.4f));
                filtered *= attenuation;
            }
        }

        return filtered;
    }

    private float applyVoiceAttenuation(float sample, float voiceResidual, float suppression) {
        if (suppression <= 0.0f) {
            return sample;
        }

        float attenuationFloor = cachedHighVoiceFilter ? 0.14f : 0.30f;
        float attenuation = Math.max(attenuationFloor, 1.0f - (voiceResidual * suppression * 6.0f));
        return sample * attenuation;
    }

    private float getDs5VoiceSuppressionStrength() {
        switch (voiceFilterMode) {
            case AudioHapticsController.VOICE_FILTER_LOW:
                return 0.22f;
            case AudioHapticsController.VOICE_FILTER_MEDIUM:
                return 0.44f;
            case AudioHapticsController.VOICE_FILTER_HIGH:
                return 1.02f;
            case AudioHapticsController.VOICE_FILTER_OFF:
            default:
                return 0.0f;
        }
    }

    private float getStandardVoiceSuppressionStrength() {
        switch (voiceFilterMode) {
            case AudioHapticsController.VOICE_FILTER_LOW:
                return 0.20f;
            case AudioHapticsController.VOICE_FILTER_MEDIUM:
                return 0.38f;
            case AudioHapticsController.VOICE_FILTER_HIGH:
                return 0.92f;
            case AudioHapticsController.VOICE_FILTER_OFF:
            default:
                return 0.0f;
        }
    }

    private String normalizeVoiceFilterMode(String mode) {
        if (AudioHapticsController.VOICE_FILTER_LOW.equals(mode) ||
                AudioHapticsController.VOICE_FILTER_MEDIUM.equals(mode) ||
                AudioHapticsController.VOICE_FILTER_HIGH.equals(mode)) {
            return mode;
        }

        return AudioHapticsController.VOICE_FILTER_OFF;
    }

    private short floatToShort(float sample) {
        int value = Math.round(Math.max(-1.0f, Math.min(1.0f, sample)) * 32767.0f);
        return (short) value;
    }

    private short toMotorValue(float normalizedLevel) {
        float clamped = Math.max(0.0f, Math.min(1.0f, normalizedLevel));
        int value = Math.round(clamped * 65535.0f);
        return (short) Math.min(65535, value);
    }
}
