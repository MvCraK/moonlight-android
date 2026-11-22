// EXTENSION DEVELOPMENT [EXT-REMOTE-VOICE] [ADDED]
package com.limelight.extensions.voice;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceKeyFanTest {
    private static final int KEYS = 5;

    @Test public void allKeysAndLabelsStayInsideUsableAreaAndDoNotOverlap() {
        int[][] windows = {{320,260},{660,260},{320,640},{640,320},{320,144},{640,100},
                {280,190},{360,244},{375,667},{100,330},{180,100},{70,680}};
        for (float density : new float[]{1,1.5f,2.625f,3}) {
            int size = Math.round(48*density), label = Math.round(15*density);
            for (int[] window : windows) {
                int w=Math.round(window[0]*density), h=Math.round(window[1]*density);
                for (int step=0;step<=100;step++) {
                    int y = Math.round((h-size)*step/100f);
                    VoiceKeyFan left = new VoiceKeyFan(KEYS), right = new VoiceKeyFan(KEYS);
                    left.place(w,h,size,y,false,density,label);
                    right.place(w,h,size,y,true,density,label);
                    String where = "window="+window[0]+"x"+window[1]+" density="+density+" y="+y;
                    assertTrue(where, left.keySize>0);
                    assertEquals(where, left.keySize,right.keySize);
                    if (w>=320*density && h>=144*density) assertEquals(where, size,left.keySize);
                    for (int i=0;i<KEYS;i++) {
                        int x=left.x[i], top=left.y[i], side=left.keySize, bottom=top+side+label;
                        assertTrue(where, x>=0 && x+side<=w && top>=0 && bottom<=h);
                        assertEquals(where, w-x-side,right.x[i]); assertEquals(where, top,right.y[i]);
                        assertTrue(where, x>=size || top>=y+size || bottom<=y);
                        for (int j=0;j<i;j++) {
                            boolean apartX = Math.abs(x-left.x[j]) >= side;
                            boolean apartY = Math.abs(top-left.y[j]) >= side+label;
                            assertTrue(where, apartX || apartY);
                        }
                    }
                }
            }
        }
    }
}
