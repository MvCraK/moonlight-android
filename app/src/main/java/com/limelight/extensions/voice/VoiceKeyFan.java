// EXTENSION DEVELOPMENT [EXT-REMOTE-VOICE] [ADDED]
package com.limelight.extensions.voice;

/** Positions are relative to the usable rectangle, with its edge clearance already removed. */
final class VoiceKeyFan {
    final int[] x = new int[4], y = new int[4];
    int keySize;

    void place(int width, int height, int mainSize, int mainTop, boolean right, float density) {
        int gap = Math.max(1, Math.round(7 * density));
        int inward = width - mainSize;
        int above = mainTop, below = height - mainSize - mainTop;
        keySize = mainSize;
        for (int radiusDp = 94; radiusDp <= 154; radiusDp += 12) {
            double radius = radiusDp * density;
            double start = -Math.min(1.48, Math.asin(Math.min(1, above / radius)));
            double end = Math.min(1.48, Math.asin(Math.min(1, below / radius)));
            boolean fits = true;
            for (int i = 0; i < x.length; i++) {
                double angle = start + (end - start) * i / 3;
                int dx = (int)Math.round(radius * Math.cos(angle));
                int dy = (int)Math.round(radius * Math.sin(angle));
                x[i] = right ? inward - dx : dx;
                y[i] = mainTop + dy;
                if (dx > inward || dy < -above || dy > below ||
                        Math.max(dx, Math.abs(dy)) < mainSize + gap) fits = false;
                for (int j = 0; j < i; j++) {
                    if (Math.max(Math.abs(x[i]-x[j]), Math.abs(y[i]-y[j])) < mainSize + gap) fits = false;
                }
            }
            if (fits) return;
        }
        // On a shallow or narrow surface, choose the grid with the largest possible hit targets.
        int best = 0, columns = 1;
        for (int cols = 4; cols >= 1; cols--) {
            int rows = (4 + cols - 1) / cols;
            int size = Math.min(mainSize, Math.min(inward / cols - gap, (height - (rows-1)*gap) / rows));
            if (size > best) { best = size; columns = cols; }
        }
        // A very narrow split window can have more space along the edge than beside the mic.
        boolean down = below >= above;
        int verticalSize = Math.min(mainSize, Math.max(above, below) / 4 - gap);
        if (verticalSize > best) {
            keySize = verticalSize;
            for (int i = 0; i < 4; i++) {
                x[i] = right ? width-keySize : 0;
                y[i] = down ? mainTop+mainSize+gap+i*(keySize+gap) : mainTop-(4-i)*(keySize+gap);
            }
            return;
        }
        keySize = Math.max(0, best);
        int rows = (4 + columns - 1) / columns;
        int gridHeight = rows*keySize + (rows-1)*gap;
        int firstY = Math.max(0, Math.min(height-gridHeight, mainTop+(mainSize-gridHeight)/2));
        for (int i = 0; i < 4; i++) {
            int left = mainSize + gap + (i%columns)*(keySize+gap);
            x[i] = right ? width-left-keySize : left;
            y[i] = firstY+(i/columns)*(keySize+gap);
        }
    }
}
