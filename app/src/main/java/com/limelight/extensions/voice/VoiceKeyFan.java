// EXTENSION DEVELOPMENT [EXT-REMOTE-VOICE] [ADDED]
package com.limelight.extensions.voice;

/**
 * Positions are relative to the usable rectangle, with its edge clearance already removed.
 * Each key occupies a {@code keySize} square plus a {@code label}-high caption below it.
 */
final class VoiceKeyFan {
    final int[] x, y;
    int keySize;

    VoiceKeyFan(int count) {
        x = new int[count];
        y = new int[count];
    }

    void place(int width, int height, int mainSize, int mainTop, boolean right, float density, int label) {
        int n = x.length;
        int gap = Math.max(1, Math.round(7 * density));
        int inward = width - mainSize;
        int above = mainTop, below = height - mainSize - mainTop;
        keySize = mainSize;
        int cellHeight = mainSize + label;
        for (int radiusDp = 94; radiusDp <= 214; radiusDp += 12) {
            double radius = radiusDp * density;
            double start = -Math.min(1.48, Math.asin(clamp(above / radius)));
            double end = Math.min(1.48, Math.asin(clamp((below - label) / radius)));
            if (end < start) continue;
            boolean fits = true;
            for (int i = 0; i < n && fits; i++) {
                double angle = start + (end - start) * i / Math.max(1, n - 1);
                int dx = (int)Math.round(radius * Math.cos(angle));
                int dy = (int)Math.round(radius * Math.sin(angle));
                x[i] = right ? inward - dx : dx;
                y[i] = mainTop + dy;
                boolean clearOfMic = dx >= mainSize + gap || dy >= mainSize + gap || dy + cellHeight <= -gap;
                if (dx > inward || dy < -above || dy > below - label || !clearOfMic) fits = false;
                for (int j = 0; j < i && fits; j++) {
                    if (Math.abs(x[i]-x[j]) < mainSize + gap && Math.abs(y[i]-y[j]) < cellHeight + gap) fits = false;
                }
            }
            if (fits) return;
        }
        // On a shallow or narrow surface, choose the grid with the largest possible hit targets.
        int best = 0, columns = 1;
        for (int cols = n; cols >= 1; cols--) {
            int rows = (n + cols - 1) / cols;
            int size = Math.min(mainSize, Math.min(inward / cols - gap, (height - (rows-1)*gap) / rows - label));
            if (size > best) { best = size; columns = cols; }
        }
        // A very narrow split window can have more space along the edge than beside the mic.
        boolean down = below >= above;
        int verticalSize = Math.min(mainSize, Math.max(above, below) / n - gap - label);
        if (verticalSize > best) {
            keySize = verticalSize;
            int step = keySize + label + gap;
            for (int i = 0; i < n; i++) {
                x[i] = right ? width-keySize : 0;
                y[i] = down ? mainTop+mainSize+gap+i*step : mainTop-(n-i)*step;
            }
            return;
        }
        keySize = Math.max(0, best);
        int rows = (n + columns - 1) / columns;
        int gridHeight = rows*(keySize+label) + (rows-1)*gap;
        int firstY = Math.max(0, Math.min(height-gridHeight, mainTop+(mainSize-gridHeight)/2));
        for (int i = 0; i < n; i++) {
            int left = mainSize + gap + (i%columns)*(keySize+gap);
            x[i] = right ? width-left-keySize : left;
            y[i] = firstY+(i/columns)*(keySize+label+gap);
        }
    }

    private static double clamp(double value) {
        return Math.max(-1, Math.min(1, value));
    }
}
